package com.nimbo.ui.telegram

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// Carátulas audio con dedupe por hash: la nube no las da en docs.
// Head-first 512KB → picture embebido → SHA-256 → un jpg por álbum.
// Caché memoria + disco (tg-covers). Nada de covers = waveform.
object TgCovers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _covers = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val covers: StateFlow<Map<String, ImageBitmap>> = _covers
    private val fetching = mutableSetOf<Long>()
    // Resueltos (con o sin arte): no re-pedir en cada recompose.
    private val settled = mutableSetOf<Long>()

    @Volatile private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    // Pura, testeable.
    fun coverHashOf(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(java.util.Locale.US, it.toInt() and 0xFF) }
    }

    fun fetch(f: TgFile, onHash: (Long, String) -> Unit = { _, _ -> }) {
        if (f.kind != TgKind.AUDIO || f.fileId == 0 || f.messageId == 0L) return
        // Hash conocido (índice): solo asegura memoria, cero descargas.
        if (f.coverHash.isNotEmpty()) {
            ensureMem(f.coverHash)
            return
        }
        // Player descargando el archivo: no pelear (TDLib cancela una).
        if (TgDownload.isFullActive(f.fileId)) return
        synchronized(fetching) {
            if (f.messageId in settled || !fetching.add(f.messageId)) return
        }
        scope.launch {
            try {
                val dir = java.io.File(app?.cacheDir ?: return@launch, "tg-covers")
                TgClient.sendAwait(TdApi.DownloadFile(f.fileId, 32, 0, 512L * 1024, true))
                // El rango puede estar ya en disco (sin updates nuevos):
                // espera evento, si no GetFile dice qué hay.
                var path = withTimeoutOrNull(30_000) {
                    TgClient.updates.first { u ->
                        val rf = (u as? TdApi.UpdateFile)?.file ?: return@first false
                        if (rf.id != f.fileId) return@first false
                        rf.local.isDownloadingCompleted || rf.local.downloadedPrefixSize >= 256 * 1024
                    }.let { (it as TdApi.UpdateFile).file.local.path }
                }?.takeIf { it.isNotEmpty() }
                if (path == null) {
                    val cur = TgClient.sendAwait(TdApi.GetFile(f.fileId)) as? TdApi.File
                    path = cur?.local?.path?.takeIf {
                        it.isNotEmpty() && (cur.local.isDownloadingCompleted || cur.local.downloadedPrefixSize >= 128 * 1024)
                    }
                }
                val pic = path?.let { picture(it) }
                if (pic == null) {
                    synchronized(fetching) { settled.add(f.messageId) }
                    return@launch
                }
                val hash = coverHashOf(pic)
                dir.mkdirs()
                val cached = java.io.File(dir, "$hash.jpg")
                if (!cached.exists()) cached.writeBytes(pic)
                decode(cached.absolutePath)?.let { _covers.value = _covers.value + (hash to it) }
                onHash(f.messageId, hash)
                synchronized(fetching) { settled.add(f.messageId) }
            } catch (e: Exception) {
            } finally {
                synchronized(fetching) { fetching.remove(f.messageId) }
            }
        }
    }

    private fun ensureMem(hash: String) {
        if (_covers.value.containsKey(hash)) return
        scope.launch {
            val f = java.io.File(app?.cacheDir ?: return@launch, "tg-covers/$hash.jpg")
            if (f.exists()) decode(f.absolutePath)?.let { _covers.value = _covers.value + (hash to it) }
        }
    }

    private fun picture(path: String): ByteArray? = runCatching {
        // Head (hasta 512KB): parsers propios, retriever no lee truncados.
        java.io.File(path).inputStream().use { it.readBytes() }.let { TgArt.picture(it) }
    }.getOrNull()

    private fun decode(path: String): ImageBitmap? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var s = 1
        while (b.outWidth / s > 512 || b.outHeight / s > 512) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        BitmapFactory.decodeFile(path, o)?.asImageBitmap()
    }.getOrNull()
}
