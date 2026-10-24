package com.nimbo.core.util

// "1:12:08" / "48:22".
fun fmtDur(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, s)
    else "%d:%02d".format(java.util.Locale.US, m, s)
}

// "14 Oct" / "14 oct" / "10月14日": formato corto del idioma. UTC fijo.
fun fmtDate(epochSec: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val d = java.time.Instant.ofEpochSecond(epochSec.toLong())
        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
    // Patrón del sistema; en tests JVM no existe → "d MMM".
    val p = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM") }
        .getOrNull() ?: "d MMM"
    return java.time.format.DateTimeFormatter.ofPattern(p, locale).format(d)
}

// Pura, testeable: "1.5 MB".
fun formatBytes(b: Long): String = when {
    b >= (1L shl 30) -> "${"%.1f".format(java.util.Locale.US, b.toDouble() / (1L shl 30))} GB"
    b >= (1L shl 20) -> "${"%.1f".format(java.util.Locale.US, b.toDouble() / (1L shl 20))} MB"
    b >= (1L shl 10) -> "${b / (1L shl 10)} KB"
    else -> "$b B"
}
