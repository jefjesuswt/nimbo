package com.nimbo.data.cloud

// Lector propio (resto de docs → visor sistema).
private val TextExts = setOf(
    "txt", "md", "markdown", "srt", "vtt", "lrc", "ass", "ssa", "log",
    "json", "csv", "tsv", "xml", "yml", "yaml", "toml", "ini", "conf", "properties", "sql",
    "sh", "py", "js", "ts", "kt", "java", "go", "rs", "c", "h", "cpp", "css", "html", "htm"
)

fun isTextFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in TextExts

// Texto con encoding detectado (BOM → UTF-8 estricto → Shift_JIS → Latin1).
// Devuelve (texto, etiqueta). Pura, testeable.
fun decodeText(bytes: ByteArray): Pair<String, String> {
    if (bytes.isEmpty()) return "" to "UTF-8"
    val fF = 0xFF.toByte()
    val fE = 0xFE.toByte()
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        return runCatching { String(bytes, 3, bytes.size - 3, Charsets.UTF_8) }.getOrDefault("") to "UTF-8"
    if (bytes.size >= 2 && bytes[0] == fF && bytes[1] == fE)
        return runCatching { String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) }.getOrDefault("") to "UTF-16LE"
    if (bytes.size >= 2 && bytes[0] == fE && bytes[1] == fF)
        return runCatching { String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) }.getOrDefault("") to "UTF-16BE"
    strict(bytes, "UTF-8")?.let { return it to "UTF-8" }
    strict(bytes, "Shift_JIS")?.let { return it to "Shift_JIS" }
    return runCatching { String(bytes, charset("windows-1252")) }.getOrDefault("") to "Latin-1"
}

private fun strict(bytes: ByteArray, cs: String): String? = runCatching {
    charset(cs).newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
}.getOrNull()
