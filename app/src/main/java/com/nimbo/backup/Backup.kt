package com.nimbo.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
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
import com.nimbo.R
import com.nimbo.ui.telegram.TgAuthState
import com.nimbo.ui.telegram.TgClient
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

// ---- Ajustes (SharedPreferences "nimbo") ----

object BackupPrefs {
    const val ON = "backup_on"
    const val TREES = "backup_trees"
    const val WIFI = "backup_wifi"
    const val CHARGING = "backup_charging"
    const val LAST = "backup_last"
    const val PENDING = "backup_pending"
    const val ERROR = "backup_error"

    fun of(ctx: Context) = ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE)
    fun trees(ctx: Context): Set<String> = of(ctx).getStringSet(TREES, emptySet()).orEmpty()
}

// ---- Registro de lo ya subido (base propia: el índice de la nube se tira en upgrades) ----

class BackupLog(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "backup_log", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS backup_log(doc_uri TEXT PRIMARY KEY, size INTEGER, " +
                "mtime INTEGER, message_id INTEGER, at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = onCreate(db)

    fun all(): Map<String, Pair<Long, Long>> =
        readableDatabase.rawQuery("SELECT doc_uri, size, mtime FROM backup_log", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1) to c.getLong(2)) }
        }

    // messageId 0 = marcado sin subir ("solo lo nuevo").
    fun mark(e: DocEntry, messageId: Long) {
        writableDatabase.replace("backup_log", null, ContentValues().apply {
            put("doc_uri", e.uri)
            put("size", e.size)
            put("mtime", e.mtime)
            put("message_id", messageId)
            put("at", System.currentTimeMillis())
        })
    }
}

object Backup {
    private const val PERIODIC = "backup"
    private const val NOW = "backup-now"
    private const val CHANNEL = "backup"
    private const val NOTIF_ID = 31
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

    private fun constraints(ctx: Context): Constraints {
        val p = BackupPrefs.of(ctx)
        return Constraints.Builder()
            .setRequiredNetworkType(if (p.getBoolean(BackupPrefs.WIFI, true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(p.getBoolean(BackupPrefs.CHARGING, false))
            .setRequiresBatteryNotLow(true)
            .build()
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
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(ctx)).build()
        )
    }

    // "Copiar ahora" y al abrir la app.
    fun runNow(ctx: Context) {
        if (!BackupPrefs.of(ctx).getBoolean(BackupPrefs.ON, false) || BackupPrefs.trees(ctx).isEmpty()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(ctx)).build()
        )
    }

    // Sesión TDLib para el Worker (la app puede no estar abierta).
    suspend fun ensureSession(ctx: Context): Boolean {
        if (TgClient.auth.value == TgAuthState.READY) return true
        val (id, hash) = TgSecrets.load(ctx)
        if (id == null || hash.isBlank()) return false
        TgClient.start(ctx, id, hash)
        val st = withTimeoutOrNull(30_000) {
            TgClient.auth.first { it == TgAuthState.READY || it == TgAuthState.WAIT_PHONE || it == TgAuthState.WAIT_CODE }
        }
        return st == TgAuthState.READY
    }

    fun foreground(ctx: Context, done: Int, total: Int): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
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
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = Backup.running.withLock {
        val ctx = applicationContext
        val prefs = BackupPrefs.of(ctx)
        if (!prefs.getBoolean(BackupPrefs.ON, false)) return@withLock Result.success()
        // Sin keys o sin login: nada que hacer (no reintentar en bucle).
        if (!Backup.ensureSession(ctx)) {
            return@withLock if (TgClient.auth.value == TgAuthState.IDLE && TgSecrets.hasKeys(ctx)) Result.retry() else Result.success()
        }
        val log = BackupLog(ctx)
        val known = log.all()
        val todo = BackupPrefs.trees(ctx).flatMap { tree ->
            val name = Backup.treeName(ctx, tree)
            newEntries(Backup.scan(ctx, tree), known).map { it to backupFolder(name, it.sub) }
        }
        prefs.edit { putInt(BackupPrefs.PENDING, todo.size) }
        if (todo.isEmpty()) {
            prefs.edit { putLong(BackupPrefs.LAST, System.currentTimeMillis()); remove(BackupPrefs.ERROR) }
            return@withLock Result.success()
        }
        runCatching { setForeground(Backup.foreground(ctx, 0, todo.size)) }
        var failed = 0
        todo.forEachIndexed { i, (e, folder) ->
            if (isStopped) return@withLock Result.retry()
            runCatching { setForeground(Backup.foreground(ctx, i, todo.size)) }
            var mid = TgRepo.uploadForBackup(ctx, Uri.parse(e.uri), folder, e.name, e.size)
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
            if (mid != null) log.mark(e, mid) else {
                failed++
                prefs.edit { putString(BackupPrefs.ERROR, "${e.name}: ${TgRepo.lastSendError ?: "upload failed"}") }
            }
            prefs.edit { putInt(BackupPrefs.PENDING, todo.size - i - 1 + failed) }
            delay(1000)
        }
        prefs.edit {
            putLong(BackupPrefs.LAST, System.currentTimeMillis())
            if (failed == 0) remove(BackupPrefs.ERROR)
        }
        TgRepo.refresh()
        // Fallidos no se marcan: van en la próxima pasada (sin bucle de reintentos).
        Result.success()
    }
}
