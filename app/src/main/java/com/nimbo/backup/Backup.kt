package com.nimbo.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nimbo.MainActivity
import com.nimbo.R
import com.nimbo.ui.telegram.TgAuthState
import com.nimbo.ui.telegram.TgClient
import com.nimbo.ui.telegram.TgFile
import com.nimbo.ui.telegram.TgRepo
import com.nimbo.ui.telegram.TgSecrets
import com.nimbo.ui.telegram.normalizePath
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// Copia automática de la galería (spec 10): carpetas elegidas por SAF,
// WorkManager cada hora con red/carga como restricciones, dedupe local.

// ---- Puras, testeables ----

private val MediaExts = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "dng",
    "mp4", "mkv", "mov", "webm", "3gp"
)

fun isMediaName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MediaExts

// Archivo visto en el teléfono. `sub` = subcarpeta (1 nivel) dentro de la elegida.
data class DocEntry(val uri: String, val name: String, val size: Long, val mtime: Long, val sub: String = "")

// Nuevo = no está en el log o cambió tamaño/fecha (editado).
fun newEntries(entries: List<DocEntry>, log: Map<String, Pair<Long, Long>>): List<DocEntry> =
    entries.filter { e -> log[e.uri].let { it == null || it.first != e.size || it.second != e.mtime } }

// Destino en la nube: Backup/<carpeta>[/<sub>].
fun backupFolder(treeName: String, sub: String = ""): String =
    normalizePath("Backup/${treeName.ifBlank { "Phone" }}/$sub")

// Segundos de espera pedidos por Telegram (FLOOD_WAIT_X o "retry after X").
fun floodWaitSec(msg: String?): Int? =
    Regex("(?:FLOOD_WAIT_|retry after )(\\d+)").find(msg ?: "")?.groupValues?.get(1)?.toIntOrNull()

// Huella de contenido: tamaño + SHA-256 del primer y último tramo. Misma
// foto con otra ruta (otra carpeta elegida, reinstalación) = misma huella.
fun fingerprint(size: Long, head: ByteArray, tail: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(size.toString().toByteArray())
    md.update(head)
    md.update(tail)
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

// ¿Ya está en la nube? Mismo destino, nombre y tamaño (cubre perder el log).
fun inCloud(cloud: List<TgFile>, folder: String, name: String, size: Long): TgFile? =
    cloud.firstOrNull { it.folder == folder && it.name == name && it.sizeBytes == size }

// Reintentos con espera creciente (1 h, 2 h, 4 h, 8 h); a los MAX_FAILS se deja.
const val MAX_FAILS = 5

fun shouldRetry(fails: Int, lastAt: Long, now: Long): Boolean =
    fails == 0 || (fails < MAX_FAILS && now - lastAt >= (3_600_000L shl (fails - 1)))

// ---- Ajustes (SharedPreferences "nimbo") ----

object BackupPrefs {
    const val ON = "backup_on"
    const val TREES = "backup_trees"
    const val WIFI = "backup_wifi"
    const val CHARGING = "backup_charging"
    const val LAST = "backup_last"
    const val PENDING = "backup_pending"
    const val ERROR = "backup_error"
    const val GAVE_UP = "backup_gave_up"

    fun of(ctx: Context) = ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE)
    fun trees(ctx: Context): Set<String> = of(ctx).getStringSet(TREES, emptySet()).orEmpty()
}

// ---- Registro de lo ya subido (base propia: el índice de la nube se tira en upgrades) ----

class BackupLog(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "backup_log", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS backup_log(doc_uri TEXT PRIMARY KEY, size INTEGER, " +
                "mtime INTEGER, message_id INTEGER, at INTEGER, fp TEXT)"
        )
        failsTable(db)
    }

    // v2: huella (dedupe por contenido) + fallos por archivo.
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("ALTER TABLE backup_log ADD COLUMN fp TEXT")
        failsTable(db)
    }

    private fun failsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fp ON backup_log(fp)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_fail(doc_uri TEXT PRIMARY KEY, fails INTEGER, at INTEGER)")
    }

    fun all(): Map<String, Pair<Long, Long>> =
        readableDatabase.rawQuery("SELECT doc_uri, size, mtime FROM backup_log", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1) to c.getLong(2)) }
        }

    // messageId 0 = marcado sin subir ("solo lo nuevo").
    fun mark(e: DocEntry, messageId: Long, fp: String? = null) {
        writableDatabase.replace("backup_log", null, ContentValues().apply {
            put("doc_uri", e.uri)
            put("size", e.size)
            put("mtime", e.mtime)
            put("message_id", messageId)
            put("at", System.currentTimeMillis())
            put("fp", fp)
        })
        writableDatabase.delete("backup_fail", "doc_uri=?", arrayOf(e.uri))
    }

    fun byFp(fp: String): Long? =
        readableDatabase.rawQuery("SELECT message_id FROM backup_log WHERE fp=? LIMIT 1", arrayOf(fp))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    fun fails(): Map<String, Pair<Int, Long>> =
        readableDatabase.rawQuery("SELECT doc_uri, fails, at FROM backup_fail", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1) to c.getLong(2)) }
        }

    // Sin "ON CONFLICT DO UPDATE": SQLite < 3.24 en Android 8-10.
    fun fail(uri: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO backup_fail(doc_uri, fails, at) VALUES(?, 0, 0)", arrayOf<Any>(uri))
        db.execSQL(
            "UPDATE backup_fail SET fails = fails + 1, at = ? WHERE doc_uri = ?",
            arrayOf<Any>(System.currentTimeMillis(), uri)
        )
    }

    // "Reintentar" en More: borra el historial de fallos.
    fun resetFails() {
        writableDatabase.delete("backup_fail", null, null)
    }
}

enum class BackupSession { READY, NO_KEYS, SIGNED_OUT, TIMEOUT }

object Backup {
    private const val TAG = "NimboBackup"
    private const val PERIODIC = "backup"
    private const val NOW = "backup-now"
    private const val CHANNEL = "backup"
    private const val NOTIF_ID = 31
    private const val NOTIF_SIGNIN = 32
    private const val EDGE = 256 * 1024
    // Un solo pase a la vez (periódico y "ahora" podrían coincidir): el
    // segundo espera y re-escanea, así no sube dos veces.
    val running = Mutex()

    fun treeName(ctx: Context, tree: String): String =
        DocumentFile.fromTreeUri(ctx, Uri.parse(tree))?.name.orEmpty()

    // Fotos y vídeos de la carpeta + subcarpetas de 1 nivel.
    fun scan(ctx: Context, tree: String): List<DocEntry> {
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return emptyList()
        val out = mutableListOf<DocEntry>()
        fun walk(d: DocumentFile, sub: String) {
            d.listFiles().forEach { k ->
                val n = k.name ?: return@forEach
                if (k.isDirectory && sub.isEmpty()) walk(k, n)
                else if (k.isFile && isMediaName(n)) out += DocEntry(k.uri.toString(), n, k.length(), k.lastModified(), sub)
            }
        }
        walk(root, "")
        return out
    }

    // Lee primer y último tramo (256 KB) sin leer el archivo entero.
    fun fingerprintOf(ctx: Context, e: DocEntry): String? = runCatching {
        ctx.contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                fun read(at: Long, n: Int): ByteArray {
                    val b = java.nio.ByteBuffer.allocate(n)
                    ch.position(at)
                    while (b.hasRemaining() && ch.read(b) > 0) Unit
                    return b.array().copyOf(b.position())
                }
                val head = read(0, minOf(EDGE.toLong(), e.size).toInt())
                val tail = if (e.size > EDGE) read(e.size - EDGE, EDGE) else ByteArray(0)
                fingerprint(e.size, head, tail)
            }
        }
    }.getOrNull()

    // manual = "Copiar ahora": orden del usuario, solo respeta Wi-Fi
    // (no gastar datos); carga y batería son para la copia automática.
    private fun constraints(ctx: Context, manual: Boolean = false): Constraints {
        val p = BackupPrefs.of(ctx)
        return Constraints.Builder()
            .setRequiredNetworkType(if (p.getBoolean(BackupPrefs.WIFI, true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(!manual && p.getBoolean(BackupPrefs.CHARGING, false))
            .setRequiresBatteryNotLow(!manual)
            .build()
    }

    // ¿La red actual cumple "solo Wi-Fi"? (para avisar "esperando Wi-Fi").
    fun networkOk(ctx: Context): Boolean {
        if (!BackupPrefs.of(ctx).getBoolean(BackupPrefs.WIFI, true)) return true
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // Aplica ajustes: periódico cada hora (o lo cancela si está apagado).
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!BackupPrefs.of(ctx).getBoolean(BackupPrefs.ON, false) || BackupPrefs.trees(ctx).isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(ctx))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
        )
    }

    // "Copiar ahora" (manual: ya, prioritaria) y al abrir la app (con las
    // restricciones normales). REPLACE en manual: no quedar tras uno atascado.
    fun runNow(ctx: Context, manual: Boolean = true) {
        if (!BackupPrefs.of(ctx).getBoolean(BackupPrefs.ON, false) || BackupPrefs.trees(ctx).isEmpty()) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(ctx, manual))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        if (manual) req.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req.build()
        )
    }

    // Sesión TDLib para el Worker (la app puede no estar abierta). Distingue
    // "sin keys" / "sesión cerrada" (no reintentar) de "TDLib no respondió".
    suspend fun ensureSession(ctx: Context): BackupSession {
        if (TgClient.auth.value == TgAuthState.READY) return BackupSession.READY
        val (id, hash) = TgSecrets.load(ctx)
        if (id == null || hash.isBlank()) return BackupSession.NO_KEYS
        TgClient.start(ctx, id, hash)
        val st = withTimeoutOrNull(45_000) {
            TgClient.auth.first { it != TgAuthState.IDLE }
        }
        return when (st) {
            TgAuthState.READY -> BackupSession.READY
            null -> BackupSession.TIMEOUT
            else -> BackupSession.SIGNED_OUT
        }
    }

    private fun channel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return nm
    }

    // Sesión cerrada: avisar una vez, tocar abre la app para iniciar sesión.
    fun notifySignIn(ctx: Context) {
        val nm = channel(ctx) ?: return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            nm.notify(
                NOTIF_SIGNIN,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(ctx.getString(R.string.backup_paused))
                    .setContentText(ctx.getString(R.string.backup_signed_out))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    fun foreground(ctx: Context, done: Int, total: Int): ForegroundInfo {
        channel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(R.string.backup_title))
            .setContentText(ctx.getString(R.string.backup_progress, done, total))
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    fun log(msg: String) = Log.i(TAG, msg)
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Expedited en Android < 12 corre como servicio en primer plano.
    override suspend fun getForegroundInfo(): ForegroundInfo = Backup.foreground(applicationContext, 0, 0)

    override suspend fun doWork(): Result = Backup.running.withLock {
        val ctx = applicationContext
        val prefs = BackupPrefs.of(ctx)
        if (!prefs.getBoolean(BackupPrefs.ON, false)) return@withLock Result.success()
        when (Backup.ensureSession(ctx)) {
            BackupSession.READY -> Unit
            BackupSession.NO_KEYS -> return@withLock Result.success()
            BackupSession.SIGNED_OUT -> {
                Backup.log("session signed out")
                prefs.edit { putString(BackupPrefs.ERROR, ctx.getString(R.string.backup_signed_out)) }
                Backup.notifySignIn(ctx)
                return@withLock Result.success()
            }
            BackupSession.TIMEOUT -> {
                Backup.log("session timeout, retry")
                return@withLock Result.retry()
            }
        }
        Backup.log("session ready (attempt ${runAttemptCount})")
        // La nube al día: base del dedupe "ya está en la nube".
        TgRepo.init(ctx)
        runCatching { TgRepo.reload() }
        val cloud = TgRepo.files.value
        val log = BackupLog(ctx)
        val known = log.all()
        val fails = log.fails()
        val now = System.currentTimeMillis()
        val fresh = BackupPrefs.trees(ctx).flatMap { tree ->
            val name = Backup.treeName(ctx, tree)
            newEntries(Backup.scan(ctx, tree), known).map { it to backupFolder(name, it.sub) }
        }
        val gaveUp = fresh.count { (fails[it.first.uri]?.first ?: 0) >= MAX_FAILS }
        val todo = fresh.filter { (e, _) ->
            val f = fails[e.uri]
            shouldRetry(f?.first ?: 0, f?.second ?: 0L, now)
        }
        Backup.log("pass: cloud=${cloud.size} new=${fresh.size} todo=${todo.size} gaveUp=$gaveUp")
        prefs.edit { putInt(BackupPrefs.PENDING, todo.size); putInt(BackupPrefs.GAVE_UP, gaveUp) }
        if (todo.isEmpty()) {
            prefs.edit { putLong(BackupPrefs.LAST, System.currentTimeMillis()); remove(BackupPrefs.ERROR) }
            return@withLock Result.success()
        }
        runCatching { setForeground(Backup.foreground(ctx, 0, todo.size)) }
        // Visible en la tarjeta de subidas de la app; su ✕ cancela el pase.
        val card = TgRepo.beginExternal(ctx.getString(R.string.backup_title), todo.map { it.first.name to it.first.size })
        var failed = 0
        var uploaded = 0
        var skipped = 0
        var bytes = 0L
        try {
            todo.forEachIndexed { i, (e, folder) ->
                if (isStopped) return@withLock Result.retry()
                if (TgRepo.isCancelled(card)) return@withLock Result.success()
                runCatching { setForeground(Backup.foreground(ctx, i, todo.size)) }
                TgRepo.externalStep(card, i, bytes, e.name, 1)
                // Dedupe 1: ya está en la nube (mismo destino, nombre y tamaño).
                val there = inCloud(cloud, folder, e.name, e.size)
                // Dedupe 2: mismo contenido ya subido desde otra ruta.
                val fp = if (there == null) Backup.fingerprintOf(ctx, e) else null
                val dup = fp?.let { log.byFp(it) }
                var mid: Long? = there?.messageId ?: dup
                if (mid != null) skipped++
                if (mid == null) {
                    mid = TgRepo.uploadForBackup(ctx, Uri.parse(e.uri), folder, e.name, e.size)
                    // Telegram pide esperar: hasta 2 min se espera aquí; más, que reintente WorkManager.
                    val wait = if (mid == null) floodWaitSec(TgRepo.lastSendError) else null
                    if (wait != null) {
                        if (wait > 120) {
                            prefs.edit { putString(BackupPrefs.ERROR, TgRepo.lastSendError) }
                            return@withLock Result.retry()
                        }
                        delay(wait * 1000L)
                        mid = TgRepo.uploadForBackup(ctx, Uri.parse(e.uri), folder, e.name, e.size)
                    }
                    delay(1000)
                    if (mid != null) uploaded++
                }
                if (mid != null) log.mark(e, mid, fp) else {
                    failed++
                    log.fail(e.uri)
                    prefs.edit { putString(BackupPrefs.ERROR, "${e.name}: ${TgRepo.lastSendError ?: "upload failed"}") }
                }
                bytes += e.size
                TgRepo.externalStep(card, i + 1, bytes, e.name, if (mid != null) 2 else 3)
                prefs.edit { putInt(BackupPrefs.PENDING, todo.size - i - 1 + failed) }
            }
        } finally {
            TgRepo.endExternal(card)
        }
        Backup.log("pass done: uploaded=$uploaded skipped=$skipped failed=$failed")
        prefs.edit {
            putLong(BackupPrefs.LAST, System.currentTimeMillis())
            if (failed == 0) remove(BackupPrefs.ERROR)
        }
        TgRepo.refresh()
        Result.success()
    }
}
