package com.nimbo.data.media

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.transfer.FileFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Pura, testeable.
fun coverHashOf(bytes: ByteArray): String {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return d.joinToString("") { "%02x".format(java.util.Locale.US, it.toInt() and 0xFF) }
}

// Carátulas de audio con dedupe por hash: la nube no las da en docs.
// Head-first 512KB → picture embebido → SHA-256 → un jpg por álbum.
// Caché memoria (con tope) + disco (tg-covers). Sin cover = waveform.
@Singleton
class CoverRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val fetcher: FileFetcher,
    private val repo: CloudRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val cache = BitmapCache<String>(BitmapCache.heapBudget(16))
    val covers: StateFlow<Map<String, ImageBitmap>> = cache.map
    private val fetching: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Resueltos (con o sin arte): no re-pedir en cada recompose.
    private val settled: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val dir get() = java.io.File(ctx.cacheDir, "tg-covers")

    fun file(hash: String): java.io.File = java.io.File(dir, "$hash.jpg")

    fun fetch(f: TgFile) {
        if (f.kind != TgKind.AUDIO || f.fileId == 0 || f.messageId == 0L) return
        // Hash conocido (índice): solo asegura memoria, cero descargas.
        if (f.coverHash.isNotEmpty()) {
            ensureMem(f.coverHash)
            return
        }
        // Player descargando el archivo: no pelear (TDLib cancela una).
        if (fetcher.isFullActive(f.fileId)) return
        if (f.messageId in settled || !fetching.add(f.messageId)) return
        scope.launch(io) {
            try {
                // El rango puede estar ya en disco: head() mira el estado actual.
                val path = fetcher.head(f.fileId, 512L * 1024, 256L * 1024, 30_000)
                val pic = path?.let { picture(it) }
                if (pic == null) {
                    settled.add(f.messageId)
                    return@launch
                }
                val hash = coverHashOf(pic)
                dir.mkdirs()
                val cached = file(hash)
                if (!cached.exists()) cached.writeBytes(pic)
                decodeSampled(cached.absolutePath, 512)?.let { cache.put(hash, it) }
                repo.noteCover(f.messageId, hash)
                settled.add(f.messageId)
            } catch (_: Exception) {
            } finally {
                fetching.remove(f.messageId)
            }
        }
    }

    private fun ensureMem(hash: String) {
        if (hash in cache) return
        scope.launch(io) {
            val f = file(hash)
            if (f.exists()) decodeSampled(f.absolutePath, 512)?.let { cache.put(hash, it) }
        }
    }

    // Head (hasta 512KB): parsers propios, el retriever no lee truncados.
    private fun picture(path: String): ByteArray? = runCatching {
        java.io.File(path).inputStream().use { it.readBytes() }.let { TgArt.picture(it) }
    }.getOrNull()
}
