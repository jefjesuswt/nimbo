package com.nimbo.ui.telegram

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

// Thumbs TDLib → bitmap en memoria (tope 60). Sin Coil: un download +
// BitmapFactory bastan (`// ponytail:`).
object TgThumbs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _maps = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val maps: StateFlow<Map<Int, ImageBitmap>> = _maps
    private val fetching = mutableSetOf<Int>()

    fun fetch(fileId: Int) {
        if (fileId == 0 || _maps.value.containsKey(fileId)) return
        synchronized(fetching) {
            if (!fetching.add(fileId)) return
        }
        scope.launch {
            try {
                val res = TgClient.sendAwait(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                val path = (res as? TdApi.File)?.local?.path?.takeIf { it.isNotEmpty() }
                val bmp = path?.let { sampledFile(it, 512) }
                if (bmp != null) _maps.value = _maps.value + (fileId to bmp)
            } catch (_: Exception) {
            } finally {
                synchronized(fetching) { fetching.remove(fileId) }
            }
        }
    }

    // Minithumbnail (bytes del mensaje): placeholder instantáneo.
    fun mini(data: ByteArray): ImageBitmap? {
        if (data.isEmpty()) return null
        return runCatching {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, b)
            val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, 128) }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)?.asImageBitmap()
        }.getOrNull()
    }

        // Versión grande para visor (2048px). Pública para PhotoViewer.
    fun full(path: String): ImageBitmap? = sampledFile(path, 2048)

    private fun sampledFile(path: String, max: Int): ImageBitmap? = runCatching {        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, max) }
        BitmapFactory.decodeFile(path, o)?.asImageBitmap()
    }.getOrNull()

    private fun sample(w: Int, h: Int, max: Int): Int {
        var s = 1
        while (w / s > max || h / s > max) s *= 2
        return s
    }
}
