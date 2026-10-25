package com.nimbo.data.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.data.transfer.FileFetcher
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Thumbs TDLib → bitmap en memoria. Sin Coil: un download + BitmapFactory
// bastan (`// ponytail:`).
@Singleton
class ThumbRepository @Inject constructor(
    private val fetcher: FileFetcher,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val cache = BitmapCache<Int>(BitmapCache.heapBudget(8))
    val maps: StateFlow<Map<Int, ImageBitmap>> = cache.map
    private val fetching: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun fetch(fileId: Int) {
        if (fileId == 0 || fileId in cache || !fetching.add(fileId)) return
        scope.launch(io) {
            try {
                fetcher.download(fileId)?.let { decodeSampled(it, 512) }?.let { cache.put(fileId, it) }
            } catch (_: Exception) {
            } finally {
                fetching.remove(fileId)
            }
        }
    }

    // Versión grande para el visor (2048 px). Llamar en IO.
    fun full(path: String): ImageBitmap? = decodeSampled(path, 2048)
}

// Minithumbnail (bytes del mensaje): placeholder instantáneo.
fun miniThumb(data: ByteArray): ImageBitmap? {
    if (data.isEmpty()) return null
    return runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, b)
        val o = BitmapFactory.Options().apply { inSampleSize = sampleSize(b.outWidth, b.outHeight, 128) }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)?.asImageBitmap()
    }.getOrNull()
}
