package com.nimbo.data.cloud

// Troceo (spec 13). Puras, testeables.

// Límite de Telegram por archivo: 2000 MiB (4000 con Premium). Margen de
// 100 MiB para no rozarlo.
const val PartLimit = 1900L * 1024 * 1024
const val PartLimitPremium = 3900L * 1024 * 1024

// (offset, largo) de cada parte; vacío si cabe entero.
fun splitPlan(size: Long, max: Long): List<Pair<Long, Long>> =
    if (size <= max) emptyList() else (0L until size step max).map { it to minOf(max, size - it) }

// Partes del mismo grupo y carpeta → un archivo (tamaño total, id de la
// primera parte presente). Las que faltan quedan fuera: incomplete().
fun groupParts(files: List<TgFile>): List<TgFile> {
    val (split, plain) = files.partition { it.isSplit() }
    val merged = split.groupBy { it.folder + "\n" + it.partGroup }.values.map { g ->
        val parts = g.flatMap { it.parts }.distinctBy { it.index }.sortedBy { it.index }
        val head = g.minBy { it.parts.first().index }
        head.copy(parts = parts, sizeBytes = parts.sumOf { it.size }, partCount = g.maxOf { it.partCount })
            .let { it.copy(meta = tgMeta(it)) }
    }
    return plain + merged
}

// Offset global → (parte, offset dentro de ella). Pasado el final →
// (sizes.size, 0).
fun partAt(offset: Long, sizes: List<Long>): Pair<Int, Long> {
    var o = offset
    sizes.forEachIndexed { i, s ->
        if (o < s) return i to o
        o -= s
    }
    return sizes.size to 0L
}
