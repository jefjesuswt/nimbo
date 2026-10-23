package com.nimbo.ui.telegram

// S.tgFiles de index.html. Estado en memoria hasta Telegram API real.
enum class TgKind { AUDIO, DOC, ARCHIVE, IMAGE, VIDEO }

data class TgFile(
    val id: String,
    val name: String,
    val kind: TgKind,
    val meta: String,
    val sizeBytes: Long = 0,
    // Nube (TDLib): ids + carpeta del caption. Defaults = mock viejo sigue.
    val messageId: Long = 0,
    val fileId: Int = 0,
    val folder: String = "",
    val dateSec: Int = 0,
    val dur: String = "",
    // Segundos (indexable/buscable); dur es solo display.
    val durSec: Int = 0,
    // 7g: metadatos + thumbs.
    val title: String = "",
    val artist: String = "",
    val thumbFileId: Int = 0,
    val thumbMini: ByteArray = byteArrayOf(),
    // Hash SHA-256 del picture (dedupe: un álbum = un jpg).
    val coverHash: String = "",
    // Id remoto persistente (para copiar sin re-subir).
    val remoteId: String = ""
) {
    // data class con ByteArray: equals/hash por contenido.
    override fun equals(other: Any?): Boolean =
        other is TgFile && id == other.id && fileId == other.fileId &&
            messageId == other.messageId && name == other.name
    override fun hashCode(): Int = 31 * id.hashCode() + messageId.hashCode()
}

fun TgFile.showTitle(): String = title.ifEmpty { name }

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


// Puras, testeables.
fun isPhoneOk(phone: String): Boolean = phone.filter { it.isDigit() }.length >= 6

fun isCodeOk(code: String): Boolean = code.length == 5 && code != "00000"

fun filterTgFiles(items: List<TgFile>, f: String): List<TgFile> = when (f) {
    "audio" -> items.filter { it.kind == TgKind.AUDIO }
    "media" -> items.filter { it.kind == TgKind.IMAGE || it.kind == TgKind.VIDEO }
    "other" -> items.filter { it.kind != TgKind.AUDIO && it.kind != TgKind.IMAGE && it.kind != TgKind.VIDEO }
    else -> items
}

// Pura, testeable: "1.5 MB".
fun formatBytes(b: Long): String = when {
    b >= (1L shl 30) -> "${"%.1f".format(java.util.Locale.US, b.toDouble() / (1L shl 30))} GB"
    b >= (1L shl 20) -> "${"%.1f".format(java.util.Locale.US, b.toDouble() / (1L shl 20))} MB"
    b >= (1L shl 10) -> "${b / (1L shl 10)} KB"
    else -> "$b B"
}

