package com.nimbo.player

// Puras del prototipo (ciclos repeat/speed/timer). Testeables.
// Desde dónde retomar: nada en cortos (< 3 min) ni si casi terminó (< 20 s).
fun resumeFrom(posMs: Long, durMs: Long): Long = when {
    posMs <= 0L -> 0L
    durMs in 1 until 3 * 60_000L -> 0L
    durMs > 0 && durMs - posMs < 20_000L -> 0L
    else -> posMs
}

// Barra "empezado" en lista/grid: 0 si no se retomaría (corto o terminado).
fun progressFrac(posMs: Long, durSec: Int): Float {
    val dur = durSec * 1000L
    return if (dur <= 0 || resumeFrom(posMs, dur) == 0L) 0f else (posMs.toFloat() / dur).coerceIn(0f, 1f)
}

enum class RepeatMode { OFF, ALL, ONE }

fun nextRepeat(cur: RepeatMode): RepeatMode = when (cur) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}

fun nextSpeed(cur: Float): Float = when (cur) {
    1f -> 1.25f
    1.25f -> 1.5f
    1.5f -> 2f
    else -> 1f
}

fun nextTimer(cur: Int): Int = when (cur) {
    0 -> 5
    5 -> 15
    15 -> 30
    else -> 0
}

// Glyph por nombre (prototipo artGlyph). Puro, testeable.
fun artGlyphFor(name: String): String {
    val n = name.lowercase()
    return when {
        "podcast" in n || "interview" in n -> "podcasts"
        "voice" in n || "memo" in n -> "mic"
        "lecture" in n || "class" in n -> "school"
        else -> "music_note"
    }
}
