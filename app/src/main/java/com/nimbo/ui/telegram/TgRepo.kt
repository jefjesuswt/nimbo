package com.nimbo.ui.telegram

import com.nimbo.R
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import org.drinkless.tdlib.TdApi

// Mapeo puro mensaje → archivo (testeable sin TDLib corriendo).
fun mapMessage(m: TdApi.Message): TgFile? {
    val caption = when (val c = m.content) {
        is TdApi.MessageAudio -> c.caption?.text.orEmpty()
        is TdApi.MessageDocument -> c.caption?.text.orEmpty()
        is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
        is TdApi.MessageVideo -> c.caption?.text.orEmpty()
        else -> return null
    }
    val folder = parseFolder(caption)
    val (mTitle, mArtist, mDur) = parseMeta(caption)
    return when (val c = m.content) {
        is TdApi.MessageAudio -> {
            val a = c.audio
            val name = a.fileName.ifEmpty { a.title.ifEmpty { mTitle.ifEmpty { "audio" } } }
            val ds = if (a.duration > 0) a.duration else mDur
            val dur = if (ds > 0) fmtDur(ds) else ""
            TgFile(
                id = m.id.toString(), name = name, kind = TgKind.AUDIO,
                meta = "", sizeBytes = a.audio.size,
                messageId = m.id, fileId = a.audio.id,
                remoteId = a.audio.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date, dur = dur, durSec = ds,
                title = a.title.ifEmpty { mTitle }, artist = a.performer.ifEmpty { mArtist },
                thumbFileId = a.albumCoverThumbnail?.file?.id ?: 0,
                thumbMini = a.albumCoverMinithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessagePhoto -> {
            val cap = c.caption?.text.orEmpty()
            // Cover generada: oculta (presta thumb, no era archivo).
            // Cover de archivo real: visible (se puede ver) + presta thumb.
            if (parseCoverLink(cap).isNotEmpty() && isGenCover(cap)) return null
            val best = c.photo.sizes.maxByOrNull { it.width * it.height }
            val f = best?.photo
            TgFile(
                id = m.id.toString(), name = parseCoverName(cap).ifEmpty { "photo-${m.id}.jpg" }, kind = TgKind.IMAGE,
                meta = "", sizeBytes = f?.size ?: 0,
                messageId = m.id, fileId = f?.id ?: 0,
                remoteId = f?.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date,
                thumbFileId = f?.id ?: 0,
                thumbMini = c.photo.minithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessageVideo -> {
            val v = c.video
            val name = v.fileName.ifEmpty { "video-${m.id}.mp4" }
            val dur = fmtDur(v.duration)
            TgFile(
                id = m.id.toString(), name = name, kind = TgKind.VIDEO,
                meta = "", sizeBytes = v.video.size,
                messageId = m.id, fileId = v.video.id,
                remoteId = v.video.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date, dur = dur, durSec = v.duration,
                thumbFileId = v.thumbnail?.file?.id ?: 0,
                thumbMini = v.minithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessageDocument -> {
            val caption = c.caption?.text.orEmpty()
            // Cover generada como documento: oculta (no era archivo).
            if (parseCoverLink(caption).isNotEmpty() && isGenCover(caption)) return null
            val d = c.document
            val name = d.fileName.ifEmpty { "file" }
            val kind = when (name.substringAfterLast('.', "").lowercase()) {
                "mp3", "m4a", "ogg", "opus", "flac", "wav", "aac" -> TgKind.AUDIO
                "zip", "7z", "tar", "rar", "gz", "xz" -> TgKind.ARCHIVE
                "jpg", "jpeg", "png", "gif", "webp", "heic" -> TgKind.IMAGE
                "mp4", "mkv", "mov", "webm" -> TgKind.VIDEO
                else -> TgKind.DOC
            }
            val isAudio = kind == TgKind.AUDIO
            val ds = if (isAudio) mDur else 0
            // Cover-documento: sin thumb del servidor → el propio archivo
            // hace de thumb (la grilla lo baja igual vía fileId).
            val selfThumb = isFolderCover(caption) && (kind == TgKind.IMAGE || kind == TgKind.VIDEO)
            TgFile(
                id = m.id.toString(), name = name, kind = kind,
                meta = "", sizeBytes = d.document.size,
                messageId = m.id, fileId = d.document.id,
                remoteId = d.document.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date,
                dur = if (ds > 0) fmtDur(ds) else "",
                durSec = ds,
                title = if (isAudio) mTitle else "",
                artist = if (isAudio) mArtist else "",
                thumbFileId = if (selfThumb) d.document.id else if (kind == TgKind.IMAGE || kind == TgKind.VIDEO) d.thumbnail?.file?.id ?: 0 else 0,
                thumbMini = if (kind == TgKind.IMAGE || kind == TgKind.VIDEO) d.minithumbnail?.data ?: byteArrayOf() else byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        else -> null
    }
}

// Cover de carpeta → (folder a fileId). Pura, testeable.
// Vale foto o documento (las covers nuevas viajan como documento para
// conservar bytes originales; las viejas como foto siguen valiendo).
fun coverLinkOf(m: TdApi.Message): Pair<String, Int>? {
    val caption = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
        is TdApi.MessageDocument -> c.caption?.text.orEmpty()
        else -> return null
    }
    if (!isFolderCover(caption)) return null
    val fid = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id
        is TdApi.MessageDocument -> c.document.document.id
        else -> null
    } ?: return null
    if (fid == 0) return null
    return parseFolder(caption) to fid
}
// subdir único (colisiones fuera, nombre intacto).
fun stageFor(ctx: Context, name: String): java.io.File {
    val safe = name.replace('/', '_').takeLast(120).ifEmpty { "file" }
    val dir = java.io.File(
        java.io.File(ctx.cacheDir, "tg-upload"),
        System.currentTimeMillis().toString()
    ).apply { mkdirs() }
    return java.io.File(dir, safe)
}

// Metadatos audio del archivo local (MediaMetadataRetriever plataforma).
// Null si no es audio o falla. Triple(título, artista, durSec).
fun audioMeta(stage: java.io.File, name: String): Triple<String, String, Int>? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext !in setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")) return null
    return runCatching {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(stage.absolutePath)
        val title = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
        val artist = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.div(1000)?.toInt() ?: 0
        r.release()
        if (title.isEmpty() && artist.isEmpty() && dur <= 0) null
        else Triple(title, artist, dur)
    }.getOrNull()
}

// Picture embebido directo del uri (archivo completo local, no truncado).
// Se usa al subir: la carátula real viaja linkeada.
fun embeddedCover(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    val pic = r.embeddedPicture
    r.release()
    pic?.takeIf { it.size in 1..5 * 1024 * 1024 }
}.getOrNull()

// Lista Saved Messages + índice. Nube manda; índice acelera/offline.
object TgRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _files = MutableStateFlow<List<TgFile>>(emptyList())
    val files: StateFlow<List<TgFile>> = _files
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // Uploads por lote: un pending por tanda/carpeta, progreso global en
    // bytes, card viva hasta fin del lote (sin parpadeo entre tracks).
    // Sin X v1: cancelar real exige DeleteMessages con id aún desconocido.
    data class UpJob(val uri: Uri, val folder: String, val name: String, val size: Long, val cover: Boolean = false, val data: ByteArray? = null, val extraTag: String = "")
    data class BatchUi(
        val id: Long, val label: String, val total: Long, val done: Long,
        val current: String, val doneFiles: Int, val totalFiles: Int,
        val running: Boolean, val down: Boolean = false, val scan: Boolean = false,
        // Detalle de cola: items con estado + arranque (para ETA).
        val items: List<BatchItem> = emptyList(),
        val startMs: Long = 0L,
        val currentFrac: Float = 0f
    )
    // Estado item: 0 pending, 1 corriendo, 2 listo, 3 fallido.
    data class BatchItem(val name: String, val size: Long, val state: Int = 0)
    private val _batches = MutableStateFlow<List<BatchUi>>(emptyList())
    val batches: StateFlow<List<BatchUi>> = _batches
    // Fallidos del último lote (para snackbar). Se limpia al leer.
    private val _failed = MutableStateFlow<List<String>>(emptyList())
    val failed: StateFlow<List<String>> = _failed
    fun clearFailed() { _failed.value = emptyList() }
    // Aviso puntual (cancelado...). Se limpia al leer.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }
    // Cancel: flag + job en vuelo + enviados (para borrar el parcial).
    private val batchJobs = mutableMapOf<Long, Job>()
    private val cancelFlags = mutableSetOf<Long>()
    private val batchSent = mutableMapOf<Long, MutableList<Long>>()

    fun cancelBatch(id: Long) {
        cancelFlags.add(id)
        batchJobs.remove(id)?.cancel()
        // Si aún no arrancó (scan/cola), la card sale ya; el finally la quita si corre.
        _batches.value = _batches.value.filterNot { it.id == id }
    }
    private val upMutex = Mutex()

    private fun updBatch(id: Long, f: (BatchUi) -> BatchUi) {
        _batches.value = _batches.value.map { if (it.id == id) f(it) else it }
    }

    @Volatile private var savedChat: Long? = null
    @Volatile private var myUserId: Long = 0L
    @Volatile private var index: TgIndex? = null
    @Volatile private var app: Context? = null
    // Covers de carpeta (folder → messageId): la generada es invisible en
    // lista, pero vive en el chat — borrar/mover carpeta la incluye.
    @Volatile private var coverMsgs: Map<String, Long> = emptyMap()
    // Marcadores de carpeta vacía (folder → messageId del texto #tgkeep).
    @Volatile private var keepMsgs: Map<String, Long> = emptyMap()
    private val _keeps = MutableStateFlow<Set<String>>(emptySet())
    val keeps: StateFlow<Set<String>> = _keeps

    fun init(ctx: Context) {
        if (index == null) index = TgIndex(ctx)
        if (app == null) app = ctx.applicationContext
    }

    fun refresh() {
        scope.launch { load() }
    }

    // Deslizar para refrescar (9c): espera a que termine.
    suspend fun reload() = load()

    // Borrado con deshacer (9b): se oculta ya y se borra en Telegram a los
    // 5 s. Si la app muere antes, no se borra (lado seguro).
    private val hidden = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val pendingDel = mutableMapOf<Long, Pair<Job, List<TgFile>>>()

    fun deleteWithUndo(files: List<TgFile>): Long {
        val ids = files.map { it.messageId }.toSet()
        hidden += ids
        _files.value = _files.value.filterNot { it.messageId in ids }
        val token = System.nanoTime()
        val job = scope.launch {
            kotlinx.coroutines.delay(5_000)
            synchronized(pendingDel) { pendingDel.remove(token) }
            val ok = runCatching {
                TgClient.sendAwait(TdApi.DeleteMessages(chat(), ids.toLongArray(), true)) !is TdApi.Error
            }.getOrDefault(false)
            if (ok) ids.forEach { runCatching { index?.delete(it) } }
            hidden -= ids
            if (!ok) refresh()
        }
        synchronized(pendingDel) { pendingDel[token] = job to files }
        return token
    }

    fun undoDelete(token: Long) {
        val (job, files) = synchronized(pendingDel) { pendingDel.remove(token) } ?: return
        job.cancel()
        hidden -= files.map { it.messageId }.toSet()
        _files.value = (_files.value + files).sortedByDescending { it.messageId }
    }

    @Volatile private var lastOkMs = 0L

    // Auto-refresh: al abrir + cada X min visible. Barato si ya está al día.
    fun refreshIfStale(maxAgeMin: Long = 5) {
        if (System.currentTimeMillis() - lastOkMs < maxAgeMin * 60 * 1000) return
        refresh()
    }

    // Posición de reproducción (spec 9a). Escritura en IO, lectura directa
    // (el player la pide ya desde un hilo IO).
    fun savePos(messageId: Long, ms: Long) {
        scope.launch { runCatching { index?.savePos(messageId, ms) } }
    }

    fun posOf(messageId: Long): Long = runCatching { index?.pos(messageId) ?: 0L }.getOrDefault(0L)

    // Cover deducida (hash): actualiza memoria + índice para no re-bajar.
    fun noteCover(messageId: Long, hash: String) {
        scope.launch {
            _files.value = _files.value.map {
                if (it.messageId == messageId) it.copy(coverHash = hash) else it
            }
            runCatching { index?.setCover(messageId, hash) }
        }
    }

    // ---- 7e Gestión (todo caption salvo delete/download) ----
    private suspend fun chat(): Long = savedChat ?: resolveSaved().also { savedChat = it }

    private suspend fun currentCaption(messageId: Long): String? {
        val m = TgClient.sendAwait(TdApi.GetMessage(chat(), messageId)) as? TdApi.Message
            ?: return null
        return when (val c = m.content) {
            is TdApi.MessageAudio -> c.caption?.text
            is TdApi.MessageDocument -> c.caption?.text
            is TdApi.MessagePhoto -> c.caption?.text
            is TdApi.MessageVideo -> c.caption?.text
            else -> null
        }
    }

    private suspend fun setCaption(messageId: Long, caption: String): Boolean {
        val res = TgClient.sendAwait(
            TdApi.EditMessageCaption(chat(), messageId, null, TdApi.FormattedText(caption, emptyArray()), false)
        )
        if (res is TdApi.Error) android.util.Log.w("TgUpload", "CAPTION-ERR $messageId: ${res.code} ${res.message}")
        return res !is TdApi.Error
    }

    // Rename = título display en #tgmeta (instantáneo; el archivo nube no se toca).
    fun renameTitle(f: TgFile, title: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching {
                val cap = currentCaption(f.messageId) ?: ""
                val (_, artist, dur) = parseMeta(cap)
                setCaption(f.messageId, tagMeta(tagFolder(cap, parseFolder(cap)), title, artist, dur))
            }.getOrDefault(false)
            if (ok) refresh()
            done(ok)
        }
    }

    // Move = reescribe tag folder (instantáneo, sin re-subir).
    // Mover varios: retag de carpeta en serie (con pausa anti-flood).
    fun moveFiles(files: List<TgFile>, dest: String, done: (Int, Int) -> Unit = { _, _ -> }) {
        val target = normalizePath(dest)
        scope.launch {
            var n = 0
            files.forEach { f ->
                val ok = runCatching {
                    val cap = currentCaption(f.messageId) ?: throw IllegalStateException("caption")
                    if (!setCaption(f.messageId, tagFolder(cap, target))) throw IllegalStateException("caption")
                    kotlinx.coroutines.delay(400)
                    true
                }.getOrDefault(false)
                if (!ok) android.util.Log.w("TgUpload", "MOVE-SKIP ${f.name}")
                else n++
            }
            if (n > 0) refresh()
            done(n, files.size)
        }
    }

    // Id remoto persistente: del row o en vivo (el índice offline no lo guarda).
    private suspend fun remoteIdOf(f: TgFile): String {
        if (f.remoteId.isNotEmpty()) return f.remoteId
        val m = TgClient.sendAwait(TdApi.GetMessage(chat(), f.messageId)) as? TdApi.Message
            ?: return ""
        return when (val c = m.content) {
            is TdApi.MessageAudio -> c.audio.audio.remote?.id.orEmpty()
            is TdApi.MessageDocument -> c.document.document.remote?.id.orEmpty()
            is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.remote?.id.orEmpty()
            is TdApi.MessageVideo -> c.video.video.remote?.id.orEmpty()
            else -> ""
        }
    }

    // Copiar (acceso rápido): nuevo mensaje con el mismo file remoto.
    // Cero bytes viajados, instantáneo del lado servidor.
    fun copyFiles(files: List<TgFile>, dest: String, done: (Int, Int) -> Unit = { _, _ -> }) {
        val target = normalizePath(dest)
        scope.launch {
            var n = 0
            val chat = chat()
            files.forEach { f ->
                val ok = runCatching {
                    val rid = remoteIdOf(f)
                    if (rid.isEmpty()) throw IllegalStateException("remote")
                    val cap = currentCaption(f.messageId) ?: tagFolder("", f.folder)
                    val caption = tagFolder(cap, target).let { TdApi.FormattedText(it, emptyArray()) }
                    val remote = TdApi.InputFileRemote(rid)
                    val content = when (f.kind) {
                        TgKind.AUDIO -> TdApi.InputMessageAudio(remote, null, 0, "", "", caption)
                        TgKind.VIDEO -> TdApi.InputMessageVideo(remote, null, null, 0, IntArray(0), 0, 0, 0, false, caption, false, null, false)
                        TgKind.IMAGE -> TdApi.InputMessagePhoto(remote, null, IntArray(0), 0, 0, caption, false, null, false)
                        else -> TdApi.InputMessageDocument(remote, null, true, caption)
                    }
                    val sent = TgClient.sendAwait(TdApi.SendMessage(chat, null, null, null, null, content))
                    if (sent is TdApi.Error) throw IllegalStateException(sent.message)
                    kotlinx.coroutines.delay(600)
                    true
                }.getOrDefault(false)
                if (!ok) android.util.Log.w("TgUpload", "COPY-SKIP ${f.name}")
                else n++
            }
            if (n > 0) refresh()
            done(n, files.size)
        }
    }

    fun delete(f: TgFile, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ids = longArrayOf(f.messageId)
            val ok = runCatching {
                val res = TgClient.sendAwait(TdApi.DeleteMessages(chat(), ids, true))
                res !is TdApi.Error
            }.getOrDefault(false)
            if (ok) {
                runCatching { index?.delete(f.messageId) }
                _files.value = _files.value.filterNot { it.messageId == f.messageId }
            }
            done(ok)
        }
    }

    // ---- 7e Gestión carpetas: todo retag (cero bytes), salvo download ----
    // Opera sobre memoria (tope 500 del listado). `// ponytail:` índice si duele.
    private fun inDir(dir: String): List<TgFile> {
        if (dir.isEmpty()) return _files.value.filter { it.folder.isEmpty() }
        val prefix = "$dir/"
        return _files.value.filter { it.folder == dir || it.folder.startsWith(prefix) }
    }

    // Rename/move carpeta: reescribe tag (subpaths intactos) + refresh.
    fun renameFolder(old: String, new: String, done: (Boolean) -> Unit = {}) {
        val target = normalizePath(new)
        scope.launch {
            val ok = runCatching {
                val chat = chat()
                inDir(old).forEach { f ->
                    val rest = if (old.isEmpty()) f.folder else f.folder.removePrefix("$old/")
                    val dest = if (target.isEmpty()) rest else if (rest.isEmpty()) target else "$target/$rest"
                    val cap = currentCaption(f.messageId) ?: return@forEach
                    if (!setCaption(f.messageId, tagFolder(cap, dest))) throw IllegalStateException("caption")
                }
                // Covers linkeadas mudan también (si no, el art se pierde).
                // Retag de carpeta: conserva #tgcover/#tggen/#tgname.
                coversUnder(old).forEach { (folder, msgId) ->
                    val rest = if (old.isEmpty()) folder else if (folder == old) "" else folder.removePrefix("$old/")
                    val dest = if (target.isEmpty()) rest else if (rest.isEmpty()) target else "$target/$rest"
                    val cap = currentCaption(msgId) ?: tagCoverLink(tagFolder("", dest))
                    if (!setCaption(msgId, tagFolder(cap, dest))) throw IllegalStateException("cover")
                }
                // Marcadores de vacía: texto sin caption → borra y recrea.
                val kmarks = keepsUnder(old)
                if (kmarks.isNotEmpty()) {
                    val chat = chat()
                    TgClient.sendAwait(TdApi.DeleteMessages(chat, kmarks.map { it.second }.toLongArray(), true))
                    kmarks.map { it.first }.distinct().forEach { kf ->
                        val rest = if (old.isEmpty()) kf else if (kf == old) "" else kf.removePrefix("$old/")
                        val dest = if (target.isEmpty()) rest else if (rest.isEmpty()) target else "$target/$rest"
                        if (dest.isNotEmpty()) {
                            val mark = TdApi.InputMessageText(TdApi.FormattedText("$TgTag$dest $TgKeepTag", emptyArray()), null, true)
                            if (TgClient.sendAwait(TdApi.SendMessage(chat, null, null, null, null, mark)) is TdApi.Error) throw IllegalStateException("keep")
                        }
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) refresh()
            done(ok)
        }
    }

    // Covers bajo dir (incluye subdirs): folder → msgId.
    private fun coversUnder(dir: String): List<Pair<String, Long>> {
        val prefix = "$dir/"
        return coverMsgs.toList().filter { (folder, _) ->
            folder == dir || (dir.isNotEmpty() && folder.startsWith(prefix))
        }
    }

    // Marcadores de carpeta vacía bajo dir (incluye subdirs).
    private fun keepsUnder(dir: String): List<Pair<String, Long>> {
        val prefix = "$dir/"
        return keepMsgs.toList().filter { (folder, _) ->
            folder == dir || (dir.isNotEmpty() && folder.startsWith(prefix)) || dir.isEmpty()
        }
    }

    // Crear carpeta virtual: marcador de texto (navega, 0 bytes).
    fun createFolder(path: String, done: (Boolean) -> Unit = {}) {
        val target = normalizePath(path)
        if (target.isEmpty()) {
            done(false)
            return
        }
        val a = app
        if (a == null) {
            done(false)
            return
        }
        init(a)
        scope.launch {
            val ok = runCatching {
                val c = chat()
                val text = "$TgTag$target $TgKeepTag"
                val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
                val res = TgClient.sendAwait(TdApi.SendMessage(c, null, null, null, null, content))
                res !is TdApi.Error
            }.getOrDefault(false)
            if (ok) refresh()
            done(ok)
        }
    }

    fun deleteFolder(dir: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ids = (inDir(dir).map { it.messageId } + coversUnder(dir).map { it.second } + keepsUnder(dir).map { it.second }).toLongArray()
            val ok = runCatching {
                if (ids.isNotEmpty()) {
                    val res = TgClient.sendAwait(TdApi.DeleteMessages(chat(), ids, true))
                    if (res is TdApi.Error) throw IllegalStateException(res.message)
                }
                true
            }.getOrDefault(false)
            if (ok) {
                runCatching {
                    ids.forEach { index?.delete(it) }
                }
                _files.value = _files.value.filterNot { it.messageId in ids.toSet() }
            }
            done(ok)
        }
    }

    // Download carpeta a Download/Nimbo/<ruta> con card de progreso.
    fun downloadFolder(ctx: Context, dir: String, done: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        scope.launch {
            val files = inDir(dir).filter { it.fileId != 0 }
            if (files.isEmpty()) {
                done(false)
                return@launch
            }
            val id = System.currentTimeMillis()
            val label = if (dir.isEmpty()) app.getString(R.string.root) else dir.substringAfterLast('/')
            _batches.value += BatchUi(
                id, label, files.size.toLong(), 0, "", 0, files.size, false, true,
                items = files.map { BatchItem(it.name, it.sizeBytes) }, startMs = id
            )
            coroutineContext[Job]?.let { batchJobs[id] = it }
            val dctx = app
            if (dctx != null) UploadService.ensure(dctx)
            try {
                upMutex.withLock {
                    var n = 0
                    files.forEach { f ->
                        if (id in cancelFlags) throw CancellationException()
                        updBatch(id) {
                            it.copy(
                                running = true, current = f.name, doneFiles = n,
                                items = it.items.map { b -> if (b.name == f.name && b.state == 0) b.copy(state = 1) else b }
                            )
                        }
                        if (dctx != null) UploadService.progress(dctx, label, n.toFloat() / files.size, f.name)
                        val ok = downloadOne(app, f, if (dir.isEmpty()) "" else "$dir/")
                        n++
                        updBatch(id) {
                            it.copy(
                                done = n.toLong(), doneFiles = n,
                                items = it.items.map { b -> if (b.name == f.name && b.state == 1) b.copy(state = if (ok) 2 else 3) else b }
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                _notice.value = app.getString(R.string.download_cancelled)
            } finally {
                batchJobs.remove(id)
                cancelFlags.remove(id)
                _batches.value = _batches.value.filterNot { it.id == id }
                if (dctx != null && _batches.value.none { it.running }) UploadService.finish(dctx)
            }
            done(true)
        }
    }

    // Descarga explícita de un archivo a Download/Nimbo.
    fun download(ctx: Context, f: TgFile, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = downloadOne(ctx.applicationContext, f, "")
            done(ok)
        }
    }

    private suspend fun downloadOne(ctx: Context, f: TgFile, sub: String): Boolean = runCatching {
        if (f.fileId == 0) return@runCatching false
        val res = TgClient.sendAwait(TdApi.DownloadFile(f.fileId, 32, 0, 0, true))
        var path = (res as? TdApi.File)?.local?.path?.takeIf { it.isNotEmpty() }
        if (path == null || !java.io.File(path).exists()) {
            path = withTimeoutOrNull(180_000) {
                TgClient.updates.first { u ->
                    val rf = (u as? TdApi.UpdateFile)?.file ?: return@first false
                    rf.id == f.fileId && rf.local.isDownloadingCompleted
                }.let { (it as TdApi.UpdateFile).file.local.path }
            }?.takeIf { it.isNotEmpty() } ?: return@runCatching false
        }
        // ponytail: Android 8-9 no tiene MediaStore.Downloads (y pediría
        // permiso de almacenamiento): sin descarga a Downloads ahí.
        if (android.os.Build.VERSION.SDK_INT < 29) return@runCatching false
        val src = java.io.File(path)
        val rel = if (sub.isEmpty()) "Download/Nimbo" else "Download/Nimbo/$sub"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeOf(f.name))
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, rel)
        }
        val uri = ctx.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return@runCatching false
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return@runCatching false
        true
    }.getOrDefault(false)

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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

    private suspend fun load() {
        if (_loading.value) return
        _loading.value = true
        try {
            val chat = savedChat ?: resolveSaved().also { savedChat = it }
            val all = mutableListOf<TgFile>()
            val links = mutableMapOf<String, Int>()
            val coverIds = mutableMapOf<String, Long>()
            val keeps = mutableMapOf<String, Long>()
            val seen = mutableSetOf<Long>()
            var from = 0L
            var pages = 0
            // Hasta página vacía (tope 20×100; scroll infinito si duele).
            // Ojo: `return@repeat` es continue, no break — por eso while.
            while (pages < 20) {
                pages++
                val res = TgClient.sendAwait(TdApi.GetChatHistory(chat, from, 0, 100, false))
                val page = (res as? TdApi.Messages)?.messages ?: emptyArray()
                if (page.isEmpty()) break
                var added = 0
                page.forEach { m ->
                    if (seen.add(m.id)) {
                        added++
                        // Marcador de carpeta vacía: no es archivo, pero navega.
                        val text = (m.content as? TdApi.MessageText)?.text?.text.orEmpty()
                        if (isKeepMark(text)) {
                            val kf = parseFolder(text)
                            if (kf.isNotEmpty() && kf !in keeps) keeps[kf] = m.id
                            return@forEach
                        }
                        val link = coverLinkOf(m)
                        if (link != null) {
                            // Clave repetida (re-subida): manda la más nueva.
                            if (!links.containsKey(link.first)) {
                                links[link.first] = link.second
                                coverIds[link.first] = m.id
                            }
                        }
                        // La generada da null (oculta); la de archivo se lista.
                        mapMessage(m)?.let { all.add(it) }
                    }
                }
                val last = page.last().id
                if (added == 0 && last == from) break
                from = last
            }
            // Hashes ya deducidos (índice): el refresh no los borra.
            val known = runCatching { index?.hashes().orEmpty() }.getOrDefault(emptyMap())
            val withKnown = all.map { f ->
                if (f.coverHash.isEmpty()) known[f.messageId]?.let { f.copy(coverHash = it) } ?: f else f
            }
            val visible = applyCoverLinks(withKnown, links).sortedByDescending { it.messageId }
            _files.value = visible.filterNot { it.messageId in hidden }
            coverMsgs = coverIds
            keepMsgs = keeps
            _keeps.value = keeps.keys
            lastOkMs = System.currentTimeMillis()
            runCatching { index?.upsert(visible) }
        } catch (e: Exception) {
            android.util.Log.w("TgUpload", "LOAD-ERR ${e.message}")
            // Offline: índice manda si hay filas.
            val cached = runCatching { index?.all().orEmpty() }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) _files.value = cached
        } finally {
            _loading.value = false
        }
    }

    private suspend fun resolveSaved(): Long {
        val me = TgClient.sendAwait(TdApi.GetMe()) as? TdApi.User
            ?: throw IllegalStateException("me")
        myUserId = me.id
        val chat = TgClient.sendAwait(TdApi.CreatePrivateChat(me.id, false)) as? TdApi.Chat
            ?: throw IllegalStateException("chat")
        return chat.id
    }

    // Abre el mensaje en el Telegram oficial (deep link tg://).
    // False si no hay app que lo atienda.
    fun openInTg(ctx: Context, f: TgFile): Boolean = runCatching {
        val uid = myUserId.takeIf { it != 0L } ?: return@runCatching false
        val i = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("tg://openmessage?user_id=$uid&message_id=${f.messageId}")
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (i.resolveActivity(ctx.packageManager) == null) return@runCatching false
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    // ---- Conflictos (file-manager: existe → qué hacer) ----
    data class Conflict(val jobIdx: Int, val name: String, val folder: String, val msgId: Long)
    data class PendingBatch(val id: Long, val label: String, val jobs: List<UpJob>, val conflicts: List<Conflict>)
    enum class ResolveMode { SKIP, DUPLICATE, REPLACE }
    private val _pending = MutableStateFlow<PendingBatch?>(null)
    val pending: StateFlow<PendingBatch?> = _pending

    // Detecta choques (mismo folder+nombre, o cover de esa carpeta) antes de subir.
    // Sin choques → lote directo; con choques → diálogo.
    private suspend fun prepareBatch(label: String, jobs: List<UpJob>) {
        val byName = _files.value.associate { (it.folder + "\n" + it.name) to it.messageId }
        val bad = mutableListOf<Conflict>()
        jobs.forEachIndexed { i, job ->
            if (job.cover) {
                val id = coverMsgs[job.folder]
                if (id != null) bad.add(Conflict(i, job.name, job.folder, id))
                val vis = byName[job.folder + "\n" + job.name]
                if (vis != null && bad.none { it.jobIdx == i }) bad.add(Conflict(i, job.name, job.folder, vis))
            } else {
                val id = byName[job.folder + "\n" + job.name]
                if (id != null) bad.add(Conflict(i, job.name, job.folder, id))
            }
        }
        if (bad.isEmpty()) runBatch(label, jobs)
        else _pending.value = PendingBatch(System.currentTimeMillis(), label, jobs, bad)
    }

    // null = cancelar el lote. REPLACE borra primero (tandas de 100).
    fun resolvePending(mode: ResolveMode?) {
        val p = _pending.value ?: return
        _pending.value = null
        if (mode == null) return
        scope.launch {
            val jobs = when (mode) {
                ResolveMode.SKIP -> p.jobs.filterIndexed { i, _ -> p.conflicts.none { it.jobIdx == i } }
                ResolveMode.DUPLICATE -> p.jobs
                ResolveMode.REPLACE -> {
                    runCatching {
                        val chat = chat()
                        p.conflicts.map { it.msgId }.distinct().chunked(100).forEach { chunk ->
                            TgClient.sendAwait(TdApi.DeleteMessages(chat, chunk.toLongArray(), true))
                        }
                    }
                    refresh()
                    p.jobs
                }
            }
            if (jobs.isNotEmpty()) runBatch(p.label, jobs)
        }
    }

    // Backup: uris → lote con tamaños → serie. Label = archivo o "N files".
    fun enqueue(ctx: Context, uris: List<Uri>, folder: String = "") {
        if (uris.isEmpty()) return
        init(ctx)
        val app = ctx.applicationContext
        scope.launch {
            val jobs = uris.mapNotNull { uri -> jobOf(app, uri, folder, false) }
            if (jobs.isEmpty()) return@launch
            val label = if (jobs.size == 1) jobs[0].name else app.resources.getQuantityString(R.plurals.files_count, jobs.size, jobs.size)
            prepareBatch(label, jobs)
        }
    }

    // Guarda traducción como hermano en la nube ("05_x.es.txt").
    // Reemplaza si ya existe. El lector lo abre sin re-traducir.
    fun saveTranslation(orig: TgFile, tgtTag: String, text: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val name = siblingName(orig.name, tgtTag)
            val bytes = text.toByteArray(Charsets.UTF_8)
            _files.value.firstOrNull { it.folder == orig.folder && it.name == name }?.let { old ->
                runCatching { TgClient.sendAwait(TdApi.DeleteMessages(chat(), longArrayOf(old.messageId), true)) }
                runCatching { index?.delete(old.messageId) }
            }
            runBatch(name, listOf(UpJob(Uri.EMPTY, orig.folder, name, bytes.size.toLong(), false, bytes, TgTransTag)))
            refresh()
            done(true)
        }
    }

    private fun jobOf(ctx: Context, uri: Uri, folder: String, cover: Boolean): UpJob? {
        val cr = ctx.contentResolver
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && ix >= 0) c.getString(ix) else uri.lastPathSegment
        } ?: uri.lastPathSegment
        if (name == null) {
            android.util.Log.w("TgUpload", "SKIP-SIN-NOMBRE $uri")
            return null
        }
        val size = cr.query(uri, null, null, null, null)?.use { c ->
            val ix = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && ix >= 0) c.getLong(ix) else 0L
        } ?: 0L
        return UpJob(uri, folder, name, size, cover)
    }

    private suspend fun runBatch(label: String, jobs: List<UpJob>) {
        val id = System.currentTimeMillis()
        val total = jobs.sumOf { it.size }.coerceAtLeast(1L)
        _batches.value += BatchUi(
            id, label, total, 0, "", 0, jobs.size, false,
            items = jobs.map { BatchItem(it.name, it.size) },
            startMs = id
        )
        coroutineContext[Job]?.let { batchJobs[id] = it }
        batchSent[id] = mutableListOf()
        val ctx = app
        if (ctx != null) UploadService.ensure(ctx)
        try {
            upMutex.withLock {
                var done = 0L
                val bad = mutableListOf<String>()
                jobs.forEachIndexed { i, job ->
                    if (id in cancelFlags) throw CancellationException()
                    updBatch(id) {
                        it.copy(
                            running = true, current = job.name, doneFiles = i, currentFrac = 0f,
                            items = it.items.mapIndexed { j, b -> if (j == i) b.copy(state = 1) else b }
                        )
                    }
                    val base = done
                    val report: (Float) -> Unit = { frac ->
                        val now = base + (frac * job.size).toLong()
                        updBatch(id) { it.copy(done = now, currentFrac = frac) }
                        if (ctx != null) UploadService.progress(ctx, label, (now.toFloat() / total).coerceIn(0f, 1f), job.name)
                    }
                    val sentIds = batchSent[id] ?: mutableListOf()
                    val ok = if (job.cover) runCover(job, report, sentIds) else runOne(job, report, sentIds)
                    if (!ok) bad.add(job.name)
                    done += job.size
                    updBatch(id) {
                        it.copy(
                            done = done, doneFiles = i + 1,
                            items = it.items.mapIndexed { j, b -> if (j == i) b.copy(state = if (ok) 2 else 3) else b }
                        )
                    }
                    refresh()
                }
                if (bad.isNotEmpty()) _failed.value = bad
                refresh()
            }
        } catch (e: CancellationException) {
            // Borra lo parcial del lote (best effort) y avisa.
            runCatching {
                val ids = batchSent[id].orEmpty().toLongArray()
                if (ids.isNotEmpty()) TgClient.sendAwait(TdApi.DeleteMessages(chat(), ids, true))
            }
            _notice.value = ctx?.getString(R.string.upload_cancelled)
            throw e
        } finally {
            batchJobs.remove(id)
            cancelFlags.remove(id)
            batchSent.remove(id)
            _batches.value = _batches.value.filterNot { it.id == id }
            if (ctx != null && _batches.value.none { it.running }) UploadService.finish(ctx)
        }
    }

    private suspend fun stageJob(job: UpJob): java.io.File? {
        val ctx = app ?: return null
        // Bytes directos (cover embebida, traducción guardada): a staging
        // con su nombre real (el servidor toma el filename del path).
        job.data?.let { bytes ->
            val stage = stageFor(ctx, job.name)
            return try {
                stage.writeBytes(bytes)
                stage
            } catch (_: Exception) {
                null
            }
        }
        val stage = stageFor(ctx, job.name)
        return try {
            ctx.contentResolver.openInputStream(job.uri)?.use { input ->
                stage.outputStream().use { input.copyTo(it) }
            } ?: run {
                android.util.Log.w("TgUpload", "STAGE-NULL ${job.name} ${job.uri}")
                return null
            }
            // Lectura vacía con tamaño esperado > 0 = fallo SAF: no enviar
            // (antes creaba clones 0B en el servidor).
            if (stage.length() == 0L && job.size > 0) {
                android.util.Log.w("TgUpload", "STAGE-VACIO ${job.name} esperado=${job.size}")
                runCatching { stage.delete() }
                return null
            }
            stage
        } catch (e: Exception) {
            android.util.Log.w("TgUpload", "STAGE-ERR ${job.name}: ${e.message}")
            null
        }
    }

    // Copia automática en la tarjeta de subidas (spec 10): mismo BatchUi que
    // un lote manual; su ✕ (cancelBatch) marca el flag que lee el Worker.
    fun beginExternal(label: String, items: List<Pair<String, Long>>): Long {
        val id = System.currentTimeMillis()
        _batches.value += BatchUi(
            id, label, items.sumOf { it.second }.coerceAtLeast(1L), 0, "", 0, items.size, true,
            items = items.map { BatchItem(it.first, it.second) }, startMs = id
        )
        return id
    }

    // state: 1 subiendo, 2 hecho, 3 fallido (ítem index-1 al terminar, index al empezar).
    fun externalStep(id: Long, index: Int, done: Long, current: String, state: Int) {
        val item = if (state == 1) index else index - 1
        updBatch(id) {
            it.copy(
                done = done, doneFiles = if (state == 1) index else index, current = current,
                items = it.items.mapIndexed { j, b -> if (j == item) b.copy(state = state) else b }
            )
        }
    }

    fun isCancelled(id: Long): Boolean = id in cancelFlags

    fun endExternal(id: Long) {
        cancelFlags.remove(id)
        _batches.value = _batches.value.filterNot { it.id == id }
    }

    // Último error de SendMessage (copia automática lee FLOOD_WAIT_X de aquí).
    @Volatile var lastSendError: String? = null
        private set

    // Copia automática (spec 10): un archivo, en serie con el resto de
    // subidas. Devuelve el messageId o null si falló.
    suspend fun uploadForBackup(ctx: Context, uri: Uri, folder: String, name: String, size: Long): Long? {
        init(ctx)
        lastSendError = null
        val sent = mutableListOf<Long>()
        val ok = upMutex.withLock { runOne(UpJob(uri, folder, name, size), {}, sent) }
        return if (ok) sent.firstOrNull() ?: 0L else null
    }

    private suspend fun runOne(job: UpJob, report: (Float) -> Unit, sentIds: MutableList<Long>): Boolean {
        val ctx = app ?: return false
        val stage = stageJob(job) ?: return false
        return try {
            val chat = savedChat ?: resolveSaved().also { savedChat = it }
            // Docs no traen metadatos de Telegram: se extraen aquí y viajan
            // en el caption (#tgmeta). Solo audio (barato, local).
            val meta = audioMeta(stage, job.name)
            var caption = tagFolder("", job.folder)
            if (job.extraTag.isNotEmpty()) caption = "$caption ${job.extraTag}"
            if (meta != null) caption = tagMeta(caption, meta.first, meta.second, meta.third)
            val content = TdApi.InputMessageDocument(
                TdApi.InputFileLocal(stage.absolutePath), null, true,
                caption.ifEmpty { null }?.let { TdApi.FormattedText(it, emptyArray()) }
            )
            val sent = withTimeoutOrNull(60_000) {
                TgClient.sendAwait(TdApi.SendMessage(chat, null, null, null, null, content))
            }
            if (sent == null) {
                android.util.Log.w("TgUpload", "SEND-TIMEOUT ${job.name}")
                throw IllegalStateException("send-timeout")
            }
            if (sent is TdApi.Error) {
                android.util.Log.w("TgUpload", "SEND-ERR ${job.name}: ${sent.code} ${sent.message}")
                lastSendError = sent.message
                throw IllegalStateException(sent.message)
            }
            // Id del archivo propio: el wait solo vale para este (antes
            // cualquier update ajeno lo daba por subido y se borraba el stage).
            val msgContent = (sent as? TdApi.Message)?.content
            val fid = (msgContent as? TdApi.MessageDocument)?.document?.document?.id
                ?: (msgContent as? TdApi.MessageAudio)?.audio?.audio?.id ?: 0
            (sent as? TdApi.Message)?.id?.let { if (it != 0L) sentIds.add(it) }
            val ok = waitJob(job, stage.canonicalPath, fid, report)
            if (!ok) android.util.Log.w("TgUpload", "WAIT-TIMEOUT ${job.name} size=${job.size}")
            ok
        } catch (e: Exception) {
            android.util.Log.w("TgUpload", "FAIL ${job.name}: ${e.message}")
            false
        } finally {
            runCatching {
                stage.delete()
                stage.parentFile?.delete()
            }
        }
    }

    // Espera fin de subida serie. Solo vale el update de NUESTRO archivo
    // (misma ruta stage o mismo id): antes un update ajeno lo daba por
    // subido, se borraba el stage y el archivo moría en silencio.
    // Reporta fracción del archivo; el driver acumula al lote.
    private suspend fun waitJob(job: UpJob, stagePath: String, fid: Int, report: (Float) -> Unit): Boolean {
        val done = withTimeoutOrNull(180_000) {
            TgClient.updates.first { u ->
                val f = (u as? TdApi.UpdateFile)?.file ?: return@first false
                // canonicalPath: TDLib devuelve /data/data y stage era /data/user.
                val mine = (stagePath.isNotEmpty() && runCatching { java.io.File(f.local.path).canonicalPath }.getOrNull() == stagePath) ||
                    (fid != 0 && f.id == fid)
                if (!mine) return@first false
                if (!f.remote.isUploadingActive && !f.remote.isUploadingCompleted) return@first false
                val tot = f.size.takeIf { it > 0 }
                    ?: f.expectedSize.takeIf { it > 0 } ?: job.size
                if (tot > 0) report((f.remote.uploadedSize.toFloat() / tot).coerceIn(0f, 1f))
                f.remote.isUploadingCompleted
            }
            true
        } ?: false
        return done
    }

    // Subir carpeta (7h1): walk SAF, archivos con ruta + cover de carpeta.
    // prefix = dir actual (subir X dentro de D → D/X/...).
    fun enqueueFolder(ctx: Context, treeUri: Uri, prefix: String = "") {
        init(ctx)
        // Card inmediata ("leyendo…"): el walk SAF tarda y la UI parecía muerta.
        val scanId = System.currentTimeMillis()
        _batches.value += BatchUi(scanId, "", 1, 0, "", 0, 0, true, scan = true)
        scope.launch {
            try {
                batchJobs[scanId] = coroutineContext[Job] ?: return@launch
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app!!, treeUri)
                    ?: return@launch
                // Base = nombre de la carpeta elegida (si no, todo cae en raíz).
                val picked = root.name ?: ""
                val base = if (prefix.isEmpty()) picked
                    else if (picked.isEmpty()) prefix else "$prefix/$picked"
                val jobs = mutableListOf<UpJob>()
                walkDir(root, base, jobs)
                if (scanId in cancelFlags) return@launch
                // Fuera la card de scan ANTES del lote: si no, tapa la de upload.
                batchJobs.remove(scanId)
                _batches.value = _batches.value.filterNot { it.id == scanId }
                if (jobs.isNotEmpty()) prepareBatch(if (base.isEmpty()) app?.resources?.getQuantityString(R.plurals.files_count, jobs.size, jobs.size).orEmpty() else base, jobs)
            } finally {
                batchJobs.remove(scanId)
                cancelFlags.remove(scanId)
                _batches.value = _batches.value.filterNot { it.id == scanId }
            }
        }
    }

    // Cover de cada carpeta con audios: cover.jpg/folder.png… si existe; si
    // no, el arte embebido del primer audio que lo traiga. Nada más.
    private fun walkDir(
        dir: androidx.documentfile.provider.DocumentFile,
        path: String,
        jobs: MutableList<UpJob>
    ) {
        val ctx = app ?: return
        val kids = dir.listFiles()
        val hasAudio = kids.any { it.isFile && it.name != null && isAudioName(it.name!!) }
        val cover = if (hasAudio) kids.firstOrNull { it.isFile && it.name != null && isCoverName(it.name!!) } else null
        kids.forEach { k ->
            val nm = k.name ?: return@forEach
            if (k.isDirectory) walkDir(k, if (path.isEmpty()) nm else "$path/$nm", jobs)
            else if (k.isFile) jobOf(ctx, k.uri, path, k == cover)?.let { jobs.add(it) }
        }
        if (hasAudio && cover == null) {
            kids.firstNotNullOfOrNull { k ->
                if (k.isFile && k.name != null && isAudioName(k.name!!)) embeddedCover(ctx, k.uri) else null
            }?.let { emb -> jobs.add(UpJob(Uri.EMPTY, path, "cover.jpg", emb.size.toLong(), true, emb)) }
        }
    }

    // Cover de carpeta: presta thumb a sus audios. La de archivo real se
    // lista (visible); la generada (embebida) queda oculta.
    private suspend fun runCover(job: UpJob, report: (Float) -> Unit, sentIds: MutableList<Long>): Boolean {
        val ctx = app ?: return false
        val stage = stageJob(job) ?: return false
        return try {
            val chat = savedChat ?: resolveSaved().also { savedChat = it }
            var caption = tagCoverLink(tagFolder("", job.folder))
            if (job.data != null) caption += " $TgGenTag"
            else caption = tagCoverName(caption, job.name)
            // Documento (no foto): Telegram no recomprime, llegan los bytes
            // originales con su nombre. El thumb se presta igual vía fileId.
            val content = TdApi.InputMessageDocument(
                TdApi.InputFileLocal(stage.absolutePath), null, true,
                TdApi.FormattedText(caption, emptyArray())
            )
            val sent = withTimeoutOrNull(60_000) {
                TgClient.sendAwait(TdApi.SendMessage(chat, null, null, null, null, content))
            }
            if (sent == null) {
                android.util.Log.w("TgUpload", "SEND-TIMEOUT cover ${job.name}")
                throw IllegalStateException("send-timeout")
            }
            if (sent is TdApi.Error) {
                android.util.Log.w("TgUpload", "SEND-ERR cover ${job.name}: ${sent.code} ${sent.message}")
                throw IllegalStateException(sent.message)
            }
            val msgContent = (sent as? TdApi.Message)?.content
            val fid = (msgContent as? TdApi.MessageDocument)?.document?.document?.id
                ?: (msgContent as? TdApi.MessagePhoto)?.photo?.sizes?.maxByOrNull { it.width * it.height }?.photo?.id ?: 0
            (sent as? TdApi.Message)?.id?.let { if (it != 0L) sentIds.add(it) }
            val ok = waitJob(job, stage.canonicalPath, fid, report)
            if (!ok) android.util.Log.w("TgUpload", "WAIT-TIMEOUT cover ${job.name}")
            ok
        } catch (e: Exception) {
            android.util.Log.w("TgUpload", "FAIL cover ${job.name}: ${e.message}")
            false
        } finally {
            runCatching {
                stage.delete()
                stage.parentFile?.delete()
            }
        }
    }
}
