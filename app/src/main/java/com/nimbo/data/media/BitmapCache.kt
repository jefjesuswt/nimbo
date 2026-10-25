package com.nimbo.data.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bitmaps decodificados con tope en bytes: al pasarse sale lo más viejo.
// Antes el mapa crecía sin límite (OOM en bibliotecas grandes).
// La UI observa `map`; quien se queda sin bitmap muestra su placeholder.
class BitmapCache<K : Any>(private val budgetBytes: Long) {
    private val entries = LinkedHashMap<K, ImageBitmap>()
    private var bytes = 0L
    private val _map = MutableStateFlow<Map<K, ImageBitmap>>(emptyMap())
    val map: StateFlow<Map<K, ImageBitmap>> = _map

    operator fun contains(k: K): Boolean = synchronized(this) { k in entries }

    fun put(k: K, bmp: ImageBitmap) = synchronized(this) {
        entries.remove(k)?.let { bytes -= sizeOf(it) }
        entries[k] = bmp
        bytes += sizeOf(bmp)
        val it = entries.entries.iterator()
        while (bytes > budgetBytes && entries.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == k) continue
            bytes -= sizeOf(e.value)
            it.remove()
        }
        _map.value = LinkedHashMap(entries)
    }

    fun clear() = synchronized(this) {
        entries.clear()
        bytes = 0L
        _map.value = emptyMap()
    }

    private fun sizeOf(b: ImageBitmap): Long =
        runCatching { b.asAndroidBitmap().allocationByteCount.toLong() }.getOrDefault(b.width * b.height * 4L)

    companion object {
        // Fracción del heap de la app (≈ LruCache recomendado).
        fun heapBudget(fraction: Int): Long = Runtime.getRuntime().maxMemory() / fraction
    }
}

// Decodifica bajando de escala hasta caber en `max` px por lado.
fun decodeSampled(path: String, max: Int): ImageBitmap? = runCatching {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b)
    val o = BitmapFactory.Options().apply { inSampleSize = sampleSize(b.outWidth, b.outHeight, max) }
    BitmapFactory.decodeFile(path, o)?.asImageBitmap()
}.getOrNull()

fun sampleSize(w: Int, h: Int, max: Int): Int {
    var s = 1
    while (w / s > max || h / s > max) s *= 2
    return s
}
