package com.nimbo.data.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.tdlib.awaitFile
import com.nimbo.core.tdlib.call
import com.nimbo.core.tdlib.mainFileId
import com.nimbo.core.tdlib.orNull
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.PartLimit
import com.nimbo.data.cloud.PartLimitPremium
import com.nimbo.data.cloud.PartTag
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgGenTag
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.TgTransTag
import com.nimbo.data.cloud.incomplete
import com.nimbo.data.cloud.isAudioName
import com.nimbo.data.cloud.isCoverName
import com.nimbo.data.cloud.isSplit
import com.nimbo.data.cloud.messageIds
import com.nimbo.data.cloud.partName
import com.nimbo.data.cloud.siblingName
import com.nimbo.data.cloud.splitPlan
import com.nimbo.data.cloud.tagCoverLink
import com.nimbo.data.cloud.tagCoverName
import com.nimbo.data.cloud.tagFolder
import com.nimbo.data.cloud.tagMeta
import com.nimbo.data.cloud.tagEnc
import com.nimbo.data.cloud.tagPart
import com.nimbo.data.cloud.encrypted
import com.nimbo.data.crypto.EncInfo
import com.nimbo.data.crypto.KeyRing
import com.nimbo.data.crypto.blockOf
import com.nimbo.data.crypto.cipherSize
import com.nimbo.data.crypto.encryptRange
import com.nimbo.data.crypto.newHeader
import com.nimbo.data.crypto.sealInfo
import com.nimbo.data.crypto.skipFully
import com.nimbo.core.prefs.AppPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// Título de un lote. La UI lo traduce (la capa de datos no conoce strings).
sealed interface BatchLabel {
    data class Name(val text: String) : BatchLabel
    data class Files(val count: Int) : BatchLabel
    data object Root : BatchLabel
    data class Encrypting(val n: Int, val total: Int) : BatchLabel
}

enum class ItemState { PENDING, RUNNING, DONE, FAILED }

data class BatchItem(val name: String, val size: Long, val state: ItemState = ItemState.PENDING)

// Un lote por tanda/carpeta: progreso global en bytes; la card vive hasta el
// fin del lote (sin parpadeo entre tracks).
data class BatchUi(
    val id: Long, val label: BatchLabel, val total: Long, val done: Long,
    val current: String, val doneFiles: Int, val totalFiles: Int,
    val running: Boolean, val down: Boolean = false, val scan: Boolean = false,
    // Detalle de cola: items con estado + arranque (para ETA).
    val items: List<BatchItem> = emptyList(),
    val startMs: Long = 0L,
    val currentFrac: Float = 0f
)

enum class TransferNotice { UPLOAD_CANCELLED, DOWNLOAD_CANCELLED }

enum class ResolveMode { SKIP, DUPLICATE, REPLACE }

data class Conflict(val jobIdx: Int, val name: String, val folder: String, val msgIds: List<Long>)
data class PendingBatch(val label: BatchLabel, val jobs: List<UpJob>, val conflicts: List<Conflict>)
data class UpJob(
    val uri: Uri, val folder: String, val name: String, val size: Long,
    val cover: Boolean = false, val data: ByteArray? = null, val extraTag: String = "",
    // Parte de un archivo troceado (spec 13): bytes [offset, offset+size).
    val part: PartTag? = null, val offset: Long = 0,
    // Cifrado (spec 12): offset/size en bytes cifrados.
    val enc: EncJob? = null,
    // #tgmeta ya conocido (cifrar lo subido: conserva títulos editados).
    val meta: String? = null
)

class EncJob(val key: ByteArray, val header: ByteArray, val plainSize: Long, val info: String)

// Subidas y descargas por lotes, en serie (un Mutex): conflictos, cancelar
// (borra lo parcial), notificación en primer plano.
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val client: TdClient,
    private val repo: CloudRepository,
    private val plain: PlainFiles,
    private val keys: KeyRing,
    private val prefs: AppPrefs,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val _batches = MutableStateFlow<List<BatchUi>>(emptyList())
    val batches: StateFlow<List<BatchUi>> = _batches
    // Fallidos del último lote (snackbar). Se limpia al leer.
    private val _failed = MutableStateFlow<List<String>>(emptyList())
    val failed: StateFlow<List<String>> = _failed
    fun clearFailed() { _failed.value = emptyList() }
    // Aviso puntual (cancelado). Se limpia al leer.
    private val _notice = MutableStateFlow<TransferNotice?>(null)
    val notice: StateFlow<TransferNotice?> = _notice
    fun clearNotice() { _notice.value = null }
    // Choques con lo que ya hay: el diálogo decide.
    private val _pending = MutableStateFlow<PendingBatch?>(null)
    val pending: StateFlow<PendingBatch?> = _pending

    // Ids únicos (antes currentTimeMillis: dos lotes en el mismo ms chocaban).
    private val ids = AtomicLong()
    // Cancel: flag + job en vuelo + enviados (para borrar el parcial).
    private val batchJobs = ConcurrentHashMap<Long, Job>()
    private val cancelFlags: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val batchSent = ConcurrentHashMap<Long, MutableList<Long>>()
    private val upMutex = Mutex()

    private fun updBatch(id: Long, f: (BatchUi) -> BatchUi) {
        _batches.update { bs -> bs.map { if (it.id == id) f(it) else it } }
    }

    private fun dropBatch(id: Long) {
        _batches.update { bs -> bs.filterNot { it.id == id } }
    }

    fun cancelBatch(id: Long) {
        cancelFlags.add(id)
        batchJobs.remove(id)?.cancel()
        // Si aún no arrancó (scan/cola), la card sale ya; el finally la quita si corre.
        dropBatch(id)
    }

    // Compartido desde otra app (ACTION_SEND): sube en cuanto haya sesión.
    fun enqueueWhenReady(uri: Uri) {
        scope.launch {
            client.auth.first { it == TgAuthState.READY }
            enqueue(listOf(uri))
        }
    }

    // Backup: uris → lote con tamaños → serie. Label = archivo o "N files".
    fun enqueue(uris: List<Uri>, folder: String = "") {
        if (uris.isEmpty()) return
        scope.launch(io) {
            val jobs = uris.mapNotNull { jobOf(it, folder, false) }
            if (jobs.isEmpty()) return@launch
            prepareBatch(if (jobs.size == 1) BatchLabel.Name(jobs[0].name) else BatchLabel.Files(jobs.size), jobs)
        }
    }

    // Subir carpeta (7h1): walk SAF, archivos con ruta + cover de carpeta.
    // prefix = dir actual (subir X dentro de D → D/X/...).
    fun enqueueFolder(treeUri: Uri, prefix: String = "") {
        // Card inmediata ("leyendo…"): el walk SAF tarda y la UI parecía muerta.
        val scanId = ids.incrementAndGet()
        _batches.update { it + BatchUi(scanId, BatchLabel.Name(""), 1, 0, "", 0, 0, true, scan = true) }
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return@launch
                // Base = nombre de la carpeta elegida (si no, todo cae en raíz).
                val picked = root.name ?: ""
                val base = if (prefix.isEmpty()) picked else if (picked.isEmpty()) prefix else "$prefix/$picked"
                val jobs = mutableListOf<UpJob>()
                walkDir(root, base, jobs)
                if (scanId in cancelFlags) return@launch
                // Fuera la card de scan ANTES del lote: si no, tapa la de upload.
                batchJobs.remove(scanId)
                dropBatch(scanId)
                if (jobs.isNotEmpty()) prepareBatch(if (base.isEmpty()) BatchLabel.Files(jobs.size) else BatchLabel.Name(base), jobs)
            } finally {
                batchJobs.remove(scanId)
                cancelFlags.remove(scanId)
                dropBatch(scanId)
            }
        }
        batchJobs[scanId] = job
        job.start()
    }

    // Detecta choques (mismo folder+nombre, o cover de esa carpeta) antes de
    // subir. Sin choques → lote directo; con choques → diálogo.
    private suspend fun prepareBatch(label: BatchLabel, jobs: List<UpJob>) {
        val byName = repo.files.value.associate { (it.folder + "\n" + it.name) to it.messageIds() }
        val bad = mutableListOf<Conflict>()
        jobs.forEachIndexed { i, job ->
            val vis = byName[job.folder + "\n" + job.name]
            val ids = if (job.cover) repo.coverMsgs[job.folder]?.let(::listOf) ?: vis else vis
            if (ids != null) bad.add(Conflict(i, job.name, job.folder, ids))
        }
        if (bad.isEmpty()) runBatch(label, jobs)
        else _pending.value = PendingBatch(label, jobs, bad)
    }

    // null = cancelar el lote. REPLACE borra primero.
    fun resolvePending(mode: ResolveMode?) {
        val p = _pending.value ?: return
        _pending.value = null
        if (mode == null) return
        scope.launch(io) {
            val jobs = when (mode) {
                ResolveMode.SKIP -> p.jobs.filterIndexed { i, _ -> p.conflicts.none { it.jobIdx == i } }
                ResolveMode.DUPLICATE -> p.jobs
                ResolveMode.REPLACE -> {
                    runCatching { repo.deleteMessages(p.conflicts.flatMap { it.msgIds }) }
                    p.jobs
                }
            }
            if (jobs.isNotEmpty()) runBatch(p.label, jobs)
        }
    }

    // Guarda traducción como hermano en la nube ("05_x.es.txt").
    // Reemplaza si ya existe. El lector lo abre sin re-traducir.
    fun saveTranslation(orig: TgFile, tgtTag: String, text: String, done: (Boolean) -> Unit) {
        scope.launch(io) {
            val name = siblingName(orig.name, tgtTag)
            val bytes = text.toByteArray(Charsets.UTF_8)
            repo.files.value.firstOrNull { it.folder == orig.folder && it.name == name }?.let { old ->
                runCatching { repo.deleteMessages(listOf(old.messageId)) }
            }
            val ok = runCatching {
                // Traducción de un cifrado: cifrada también (es su contenido).
                runBatch(BatchLabel.Name(name), listOf(UpJob(Uri.EMPTY, orig.folder, name, bytes.size.toLong(), false, bytes, TgTransTag)), orig.encrypted())
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    // Cifrar lo ya subido (spec 12c), de a uno: bajar en claro, subir cifrado
    // y borrar el original solo cuando la copia cifrada ya sale en la lista
    // con su nombre y tamaño. Cancelar la card para todo; lo que falte sigue
    // en claro. ponytail: las covers generadas (#tggen) se quedan en claro.
    private val _encrypting = MutableStateFlow(false)
    val encrypting: StateFlow<Boolean> = _encrypting

    fun encryptExisting() {
        if (keys.key.value == null || !_encrypting.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                val todo = toEncrypt(repo.files.value)
                val failed = mutableListOf<String>()
                todo.forEachIndexed { i, f ->
                    // Bloqueado a mitad: no hay con qué cifrar.
                    val ok = keys.key.value != null && encryptOne(f, BatchLabel.Encrypting(i + 1, todo.size))
                    if (!ok) failed.add(f.name)
                }
                if (failed.isNotEmpty()) _failed.value = failed
            } finally {
                _encrypting.value = false
            }
        }
    }

    private suspend fun encryptOne(f: TgFile, label: BatchLabel): Boolean {
        val path = fetchPlain(f, label) ?: return false
        val file = File(path)
        try {
            if (file.length() != f.sizeBytes) return false
            val meta = when {
                f.kind != TgKind.AUDIO -> ""
                f.title.isEmpty() && f.artist.isEmpty() && f.durSec <= 0 -> null
                else -> tagMeta("", f.title, f.artist, f.durSec)
            }
            val job = UpJob(Uri.fromFile(file), f.folder, f.name, f.sizeBytes, meta = meta)
            if (!runBatch(label, listOf(job), forceEnc = true)) return false
            // El refresh del lote es asíncrono: se reintenta un par de veces.
            repeat(3) {
                repo.reload()
                val there = repo.files.value.any {
                    it.encrypted() && !it.locked && it.folder == f.folder && it.name == f.name && it.sizeBytes == f.sizeBytes
                }
                if (there) return repo.deleteMessages(f.messageIds())
                delay(2_000)
            }
            return false
        } finally {
            // Copia en claro fuera del teléfono: la de TDLib o la unida.
            withContext(NonCancellable) { if (f.isSplit()) file.delete() else client.send(TdApi.DeleteFile(f.fileId)) }
        }
    }

    // Bajada con card propia (el lote de subida aún no existe).
    private suspend fun fetchPlain(f: TgFile, label: BatchLabel): String? {
        val id = ids.incrementAndGet()
        _batches.update {
            it + BatchUi(
                id, label, f.sizeBytes.coerceAtLeast(1L), 0, f.name, 0, 1, true, down = true,
                items = listOf(BatchItem(f.name, f.sizeBytes, ItemState.RUNNING)), startMs = System.currentTimeMillis()
            )
        }
        coroutineContext[Job]?.let { batchJobs[id] = it }
        UploadService.ensure(ctx)
        return try {
            plain.path(f) { p ->
                updBatch(id) { it.copy(done = (p * f.sizeBytes).toLong(), currentFrac = p) }
                UploadService.progress(ctx, label, p, f.name, down = true)
            }
        } finally {
            batchJobs.remove(id)
            cancelFlags.remove(id)
            dropBatch(id)
            if (_batches.value.none { it.running }) UploadService.finish(ctx)
        }
    }

    private fun jobOf(uri: Uri, folder: String, cover: Boolean): UpJob? {
        val (name, size) = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            if (!c.moveToFirst()) null
            else (if (iName >= 0) c.getString(iName) else null) to (if (iSize >= 0) c.getLong(iSize) else 0L)
        } ?: (uri.lastPathSegment to 0L)
        if (name == null) {
            Log.w(TAG, "SKIP-SIN-NOMBRE")
            return null
        }
        return UpJob(uri, folder, name, size, cover)
    }

    // Devuelve true si todo el lote subió.
    private suspend fun runBatch(label: BatchLabel, input: List<UpJob>, forceEnc: Boolean = false): Boolean {
        val jobs = splitJobs(
            encryptJobs(input, forceEnc) ?: run {
                _failed.value = input.map { it.name }
                return false
            }
        )
        val id = ids.incrementAndGet()
        val total = jobs.sumOf { it.size }.coerceAtLeast(1L)
        _batches.update {
            it + BatchUi(
                id, label, total, 0, "", 0, jobs.size, false,
                items = jobs.map { j -> BatchItem(j.name, j.size) },
                startMs = System.currentTimeMillis()
            )
        }
        coroutineContext[Job]?.let { batchJobs[id] = it }
        val sent = mutableListOf<Long>().also { batchSent[id] = it }
        UploadService.ensure(ctx)
        var bad = emptyList<String>()
        try {
            upMutex.withLock {
                var done = 0L
                val failed = mutableListOf<String>()
                jobs.forEachIndexed { i, job ->
                    if (id in cancelFlags) throw CancellationException()
                    updBatch(id) {
                        it.copy(
                            running = true, current = job.name, doneFiles = i, currentFrac = 0f,
                            items = it.items.mapIndexed { j, b -> if (j == i) b.copy(state = ItemState.RUNNING) else b }
                        )
                    }
                    val base = done
                    val report: (Float) -> Unit = { frac ->
                        val now = base + (frac * job.size).toLong()
                        updBatch(id) { it.copy(done = now, currentFrac = frac) }
                        UploadService.progress(ctx, label, (now.toFloat() / total).coerceIn(0f, 1f), job.name)
                    }
                    val ok = uploadOne(job, report, sent)
                    if (!ok) failed.add(job.name)
                    done += job.size
                    updBatch(id) {
                        it.copy(
                            done = done, doneFiles = i + 1,
                            items = it.items.mapIndexed { j, b -> if (j == i) b.copy(state = if (ok) ItemState.DONE else ItemState.FAILED) else b }
                        )
                    }
                    repo.refresh()
                }
                bad = failed
                if (failed.isNotEmpty()) _failed.value = failed
                repo.refresh()
            }
        } catch (e: CancellationException) {
            // Borra lo parcial del lote (best effort) y avisa.
            withContext(NonCancellable) {
                runCatching { repo.deleteMessages(sent.toList()) }
            }
            _notice.value = TransferNotice.UPLOAD_CANCELLED
            throw e
        } finally {
            batchJobs.remove(id)
            cancelFlags.remove(id)
            batchSent.remove(id)
            dropBatch(id)
            if (_batches.value.none { it.running }) UploadService.finish(ctx)
        }
        return bad.isEmpty()
    }

    // Cifrado (spec 12): nombre opaco, tamaño cifrado y la info real en
    // #enc. Las covers generadas no se suben (serían arte en claro); un
    // cover.jpg viaja como imagen cifrada más. Activo pero sin clave → null:
    // nada se sube en claro por accidente.
    private fun encryptJobs(jobs: List<UpJob>, force: Boolean): List<UpJob>? {
        if (!force && !prefs.encryptUploads) return jobs
        val key = keys.key.value ?: return null
        return jobs.filterNot { it.cover && it.data != null }.map { j ->
            val header = newHeader()
            val meta = j.meta ?: if (j.data != null) "" else
                audioMeta(j.name) { it.setDataSource(ctx, j.uri) }?.let { (t, a, d) -> tagMeta("", t, a, d) }.orEmpty()
            j.copy(
                name = UUID.randomUUID().toString().take(12) + ".nimbo",
                size = cipherSize(j.size, blockOf(header)), cover = false,
                enc = EncJob(key, header, j.size, sealInfo(key, EncInfo(j.name, j.size, meta)))
            )
        }
    }

    // Más grande que el límite de Telegram → partes .001, .002… (spec 13).
    // Cada parte es un item del lote: cancelar borra las ya subidas.
    private suspend fun splitJobs(jobs: List<UpJob>): List<UpJob> {
        if (jobs.none { it.size > PartLimit }) return jobs
        val premium = client.call<TdApi.User>(TdApi.GetMe()).orNull()?.isPremium == true
        val max = if (premium) PartLimitPremium else PartLimit
        return jobs.flatMap { j ->
            val plan = splitPlan(j.size, max)
            if (plan.isEmpty() || j.data != null) return@flatMap listOf(j)
            val group = UUID.randomUUID().toString().take(8)
            plan.mapIndexed { i, (off, len) ->
                j.copy(name = partName(j.name, i + 1), size = len, offset = off, part = PartTag(i + 1, plan.size, group))
            }
        }
    }

    // subdir único (colisiones fuera, nombre intacto).
    private fun stageFor(name: String): File {
        val safe = name.replace('/', '_').takeLast(120).ifEmpty { "file" }
        val dir = File(File(ctx.cacheDir, "tg-upload"), ids.incrementAndGet().toString() + "-" + System.nanoTime())
            .apply { mkdirs() }
        return File(dir, safe)
    }

    private fun stageJob(job: UpJob): File? {
        val stage = stageFor(job.name)
        return try {
            // Bytes directos (cover embebida, traducción guardada): a staging
            // con su nombre real (el servidor toma el filename del path).
            job.enc?.let { e ->
                val input = job.data?.inputStream() ?: ctx.contentResolver.openInputStream(job.uri) ?: return null
                input.use { stage.outputStream().use { out -> encryptRange(e.key, e.header, it, e.plainSize, out, job.offset, job.size) } }
                // Cifrado corto = no se podría descifrar: no se envía.
                if (stage.length() != job.size) {
                    Log.w(TAG, "STAGE-CIFRADO-CORTO ${stage.length()}/${job.size}")
                    runCatching { stage.delete() }
                    return null
                }
                return stage
            }
            if (job.data != null) {
                stage.writeBytes(job.data)
                return stage
            }
            ctx.contentResolver.openInputStream(job.uri)?.use { input ->
                stage.outputStream().use { if (job.part == null) input.copyTo(it) else copyRange(input, it, job.offset, job.size) }
            } ?: run {
                Log.w(TAG, "STAGE-NULL")
                return null
            }
            // Parte corta = archivo unido corrupto: no se envía.
            if (job.part != null && stage.length() != job.size) {
                Log.w(TAG, "STAGE-PARTE-CORTA ${stage.length()}/${job.size}")
                runCatching { stage.delete() }
                return null
            }
            // Lectura vacía con tamaño esperado > 0 = fallo SAF: no enviar
            // (antes creaba clones 0B en el servidor).
            if (stage.length() == 0L && job.size > 0) {
                Log.w(TAG, "STAGE-VACIO esperado=${job.size}")
                runCatching { stage.delete() }
                return null
            }
            stage
        } catch (e: Exception) {
            Log.w(TAG, "STAGE-ERR ${e.message}")
            null
        }
    }

    // Documento (no foto): Telegram no recomprime, llegan los bytes
    // originales con su nombre. Cover de carpeta: presta thumb a sus audios;
    // la de archivo real se lista, la generada (embebida) queda oculta.
    private suspend fun uploadOne(job: UpJob, report: (Float) -> Unit, sentIds: MutableList<Long>): Boolean {
        val stage = withContext(io) { stageJob(job) } ?: return false
        return try {
            var caption = tagFolder("", job.folder)
            if (job.cover) {
                caption = tagCoverLink(caption)
                caption = if (job.data != null) "$caption $TgGenTag" else tagCoverName(caption, job.name)
            } else {
                if (job.extraTag.isNotEmpty()) caption = "$caption ${job.extraTag}"
                job.part?.let { caption = tagPart(caption, it) }
                // Cifrado: #tgmeta viaja dentro de #enc, nada en claro.
                job.enc?.let { caption = tagEnc(caption, it.info) }
                // Docs no traen metadatos de Telegram: se extraen aquí y viajan
                // en el caption (#tgmeta). Solo audio (barato, local).
                if (job.enc == null) {
                    audioMeta(job.name) { it.setDataSource(stage.absolutePath) }?.let { (t, a, d) -> caption = tagMeta(caption, t, a, d) }
                }
            }
            val content = TdApi.InputMessageDocument(
                TdApi.InputFileLocal(stage.absolutePath), null, true,
                caption.trim().ifEmpty { null }?.let { TdApi.FormattedText(it, emptyArray()) }
            )
            val sent = withTimeoutOrNull(60_000) {
                client.send(TdApi.SendMessage(repo.chat(), null, null, null, null, content))
            } ?: error("send-timeout")
            if (sent is TdApi.Error) error("send ${sent.code} ${sent.message}")
            val msg = sent as? TdApi.Message ?: error("send")
            if (msg.id != 0L) sentIds.add(msg.id)
            // Solo vale el estado de NUESTRO archivo (por id): antes un update
            // ajeno lo daba por subido y se borraba el stage.
            val fid = msg.content.mainFileId().takeIf { it != 0 } ?: error("no file id")
            val ok = waitUpload(fid, job.size, report)
            if (!ok) Log.w(TAG, "WAIT-TIMEOUT size=${job.size}")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "FAIL ${e.message}")
            false
        } finally {
            runCatching {
                stage.delete()
                stage.parentFile?.delete()
            }
        }
    }

    // Reporta la fracción del archivo; el lote acumula. Timeout = sin
    // progreso en 3 min.
    private suspend fun waitUpload(fid: Int, size: Long, report: (Float) -> Unit): Boolean {
        var last: TdApi.File? = null
        while (true) {
            val f = withTimeoutOrNull(180_000) {
                client.file(fid).first { it != null && it !== last }
            } ?: return false
            if (f.remote.isUploadingCompleted) return true
            val tot = f.size.takeIf { it > 0 } ?: f.expectedSize.takeIf { it > 0 } ?: size
            if (tot > 0) report((f.remote.uploadedSize.toFloat() / tot).coerceIn(0f, 1f))
            last = f
        }
    }

    // Cover de cada carpeta con audios: cover.jpg/folder.png… si existe; si
    // no, el arte embebido del primer audio que lo traiga. Nada más.
    private fun walkDir(dir: DocumentFile, path: String, jobs: MutableList<UpJob>) {
        val kids = dir.listFiles()
        val hasAudio = kids.any { it.isFile && it.name?.let(::isAudioName) == true }
        val cover = if (hasAudio) kids.firstOrNull { it.isFile && it.name?.let(::isCoverName) == true } else null
        kids.forEach { k ->
            val nm = k.name ?: return@forEach
            if (k.isDirectory) walkDir(k, if (path.isEmpty()) nm else "$path/$nm", jobs)
            else if (k.isFile) jobOf(k.uri, path, k == cover)?.let { jobs.add(it) }
        }
        if (hasAudio && cover == null) {
            kids.firstNotNullOfOrNull { k ->
                if (k.isFile && k.name?.let(::isAudioName) == true) embeddedCover(ctx, k.uri) else null
            }?.let { emb -> jobs.add(UpJob(Uri.EMPTY, path, "cover.jpg", emb.size.toLong(), true, emb)) }
        }
    }

    // ---- Descargas a Download/Nimbo ----

    // Descarga explícita de un archivo a Download/Nimbo.
    suspend fun download(f: TgFile): Boolean = scope.async(io) { downloadOne(f, "") }.await()

    // Carpeta a Download/Nimbo/<ruta> con card de progreso.
    fun downloadFolder(dir: String) {
        scope.launch(io) {
            val files = repo.inDir(dir).filter { it.fileId != 0 }
            if (files.isEmpty()) return@launch
            val id = ids.incrementAndGet()
            val label = if (dir.isEmpty()) BatchLabel.Root else BatchLabel.Name(dir.substringAfterLast('/'))
            _batches.update {
                it + BatchUi(
                    id, label, files.size.toLong(), 0, "", 0, files.size, false, true,
                    items = files.map { f -> BatchItem(f.name, f.sizeBytes) }, startMs = System.currentTimeMillis()
                )
            }
            coroutineContext[Job]?.let { batchJobs[id] = it }
            UploadService.ensure(ctx)
            try {
                upMutex.withLock {
                    files.forEachIndexed { n, f ->
                        if (id in cancelFlags) throw CancellationException()
                        updBatch(id) {
                            it.copy(
                                running = true, current = f.name, doneFiles = n,
                                items = it.items.mapIndexed { j, b -> if (j == n) b.copy(state = ItemState.RUNNING) else b }
                            )
                        }
                        UploadService.progress(ctx, label, n.toFloat() / files.size, f.name, down = true)
                        val ok = downloadOne(f, if (dir.isEmpty()) "" else "$dir/")
                        updBatch(id) {
                            it.copy(
                                done = n + 1L, doneFiles = n + 1,
                                items = it.items.mapIndexed { j, b -> if (j == n) b.copy(state = if (ok) ItemState.DONE else ItemState.FAILED) else b }
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                _notice.value = TransferNotice.DOWNLOAD_CANCELLED
            } finally {
                batchJobs.remove(id)
                cancelFlags.remove(id)
                dropBatch(id)
                if (_batches.value.none { it.running }) UploadService.finish(ctx)
            }
        }
    }

    // Partes y cifrados se unen/descifran directo al archivo de Downloads.
    private suspend fun downloadOne(f: TgFile, sub: String): Boolean {
        // ponytail: Android 8-9 no tiene MediaStore.Downloads (y pediría
        // permiso de almacenamiento): sin descarga a Downloads ahí.
        if (Build.VERSION.SDK_INT < 29 || f.incomplete() || f.locked) return false
        val uri = insertDownload(f.name, sub) ?: return false
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { plain.write(f, it) } ?: false
        }.getOrDefault(false)
        if (!ok) runCatching { ctx.contentResolver.delete(uri, null, null) }
        return ok
    }

    @RequiresApi(29)
    private fun insertDownload(name: String, sub: String): Uri? {
        val rel = if (sub.isEmpty()) "Download/Nimbo" else "Download/Nimbo/$sub"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, rel)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private companion object {
        const val TAG = "Transfers"
    }
}

// Lo que sigue en claro y se puede cifrar (entero, con contenido).
fun toEncrypt(files: List<TgFile>): List<TgFile> =
    files.filter { !it.encrypted() && !it.incomplete() && it.fileId != 0 }

// Copia [offset, offset+len) del stream.
// ponytail: en SAF las partes altas leen y tiran lo previo; FileChannel
// con position() si tarda demasiado.
fun copyRange(input: InputStream, out: OutputStream, offset: Long, len: Long) {
    val buf = ByteArray(256 * 1024)
    skipFully(input, offset)
    var left = len
    while (left > 0) {
        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) return
        out.write(buf, 0, r)
        left -= r
    }
}

// Metadatos audio del archivo local (MediaMetadataRetriever plataforma).
// Null si no es audio o falla. Triple(título, artista, durSec).
// source: del stage (path) o del uri original (cifrados: el stage no es audio).
fun audioMeta(name: String, source: (android.media.MediaMetadataRetriever) -> Unit): Triple<String, String, Int>? {
    if (!isAudioName(name)) return null
    return runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            source(r)
            val title = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000)?.toInt() ?: 0
            if (title.isEmpty() && artist.isEmpty() && dur <= 0) null else Triple(title, artist, dur)
        } finally {
            r.release()
        }
    }.getOrNull()
}

// Picture embebido directo del uri (archivo completo local, no truncado).
// Se usa al subir: la carátula real viaja linkeada.
fun embeddedCover(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.embeddedPicture?.takeIf { it.size in 1..5 * 1024 * 1024 }
    } finally {
        r.release()
    }
}.getOrNull()

fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "7z" -> "application/x-7z-compressed"
    else -> "*/*"
}
