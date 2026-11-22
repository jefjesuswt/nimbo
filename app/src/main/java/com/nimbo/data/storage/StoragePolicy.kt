package com.nimbo.data.storage

import java.io.File
import org.drinkless.tdlib.TdApi

// Cuánto se queda Nimbo en el teléfono. Todo es caché: el original está en
// Telegram y se vuelve a bajar al abrirlo.
object StoragePolicy {
    // Archivos de TDLib (filesDir/tdlib: lo abierto, reproducido, miniaturas).
    const val TDLIB_CAP = 1L shl 30
    const val TDLIB_TTL_SEC = 7 * 24 * 3600

    // Lo creado hace menos de esto no se toca en el recorte automático: un
    // visor o un vídeo puede tener el path recién devuelto.
    const val IMMUNITY_SEC = 3600

    const val TRIM_EVERY_MS = 30 * 60_000L

    // Staging de subidas en curso: nunca se borra a mano (se limpia sola).
    const val UPLOAD_DIR = "tg-upload"

    // Recorte automático: tope de tamaño + lo no abierto en una semana.
    fun autoTrim(): TdApi.OptimizeStorage = optimize(TDLIB_CAP, TDLIB_TTL_SEC, Int.MAX_VALUE, IMMUNITY_SEC)

    // "Vaciar" en Ajustes: todo lo que TDLib no esté usando ahora.
    fun clearAll(): TdApi.OptimizeStorage = optimize(0, 0, 0, 0)

    // Valores explícitos: -1 sería el límite por defecto de TDLib.
    private fun optimize(size: Long, ttl: Int, count: Int, immunity: Int) = TdApi.OptimizeStorage(
        size, ttl, count, immunity,
        emptyArray(), longArrayOf(), longArrayOf(),
        false, 0
    )
}

// Bytes de un árbol (0 si no existe).
fun dirSize(f: File): Long = when {
    !f.exists() -> 0
    f.isFile -> f.length()
    else -> f.listFiles()?.sumOf { dirSize(it) } ?: 0
}

// Borra los hijos de root salvo los de keep. Devuelve los bytes liberados.
fun clearDir(root: File, keep: Set<String>): Long =
    root.listFiles()?.filter { it.name !in keep }?.sumOf { child ->
        val size = dirSize(child)
        if (child.deleteRecursively()) size else size - dirSize(child)
    } ?: 0
