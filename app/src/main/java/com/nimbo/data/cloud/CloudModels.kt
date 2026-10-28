package com.nimbo.data.cloud

import com.nimbo.core.util.fmtDate
import com.nimbo.core.util.formatBytes

enum class TgKind { AUDIO, DOC, ARCHIVE, IMAGE, VIDEO }

// Pestañas de la nube; `key` es el de las prefs de antes ("sort_audio").
enum class CloudTab(val key: String) { FILES("files"), AUDIO("audio"), PHOTOS("photos") }

data class TgFile(
    val id: String,
    val name: String,
    val kind: TgKind,
    val meta: String,
    val sizeBytes: Long = 0,
    // Nube (TDLib): ids + carpeta del caption.
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
    val remoteId: String = "",
    // Troceo (spec 13): partes presentes en orden; vacío = archivo normal.
    val parts: List<TgPart> = emptyList(),
    val partCount: Int = 0,
    val partGroup: String = "",
    // Cifrado (spec 12): info cifrada del caption; vacío = en claro.
    // locked = no hay clave en este teléfono (nombre opaco).
    val encInfo: String = "",
    val locked: Boolean = false
) {
    // Igualdad por todos los campos (ByteArray por contenido). Antes solo
    // ids+nombre: renombrar el título o deducir la cover daba una lista
    // "igual" y el StateFlow no emitía (la UI no se enteraba).
    override fun equals(other: Any?): Boolean =
        other is TgFile && id == other.id && name == other.name && kind == other.kind &&
            meta == other.meta && sizeBytes == other.sizeBytes && messageId == other.messageId &&
            fileId == other.fileId && folder == other.folder && dateSec == other.dateSec &&
            dur == other.dur && durSec == other.durSec && title == other.title &&
            artist == other.artist && thumbFileId == other.thumbFileId &&
            thumbMini.contentEquals(other.thumbMini) && coverHash == other.coverHash &&
            remoteId == other.remoteId && parts == other.parts && partCount == other.partCount &&
            partGroup == other.partGroup && encInfo == other.encInfo && locked == other.locked

    override fun hashCode(): Int = 31 * id.hashCode() + messageId.hashCode()
}

data class TgPart(val index: Int, val messageId: Long, val fileId: Int, val size: Long)

fun TgFile.isSplit(): Boolean = partCount > 0

fun TgFile.incomplete(): Boolean = parts.size < partCount

fun TgFile.encrypted(): Boolean = encInfo.isNotEmpty()

// Mensajes que forman el archivo (borrar/mover actúan sobre todos).
fun TgFile.messageIds(): List<Long> = if (parts.isEmpty()) listOf(messageId) else parts.map { it.messageId }

fun TgFile.showTitle(): String = title.ifEmpty { name }

fun TgFile.isPlayable(): Boolean = kind == TgKind.AUDIO || kind == TgKind.VIDEO

// Filtro de la pestaña Archivos.
enum class KindFilter { ALL, AUDIO, MEDIA, OTHER }

fun filterTgFiles(items: List<TgFile>, f: KindFilter): List<TgFile> = when (f) {
    KindFilter.AUDIO -> items.filter { it.kind == TgKind.AUDIO }
    KindFilter.MEDIA -> items.filter { it.kind == TgKind.IMAGE || it.kind == TgKind.VIDEO }
    KindFilter.OTHER -> items.filter { it.kind != TgKind.AUDIO && it.kind != TgKind.IMAGE && it.kind != TgKind.VIDEO }
    KindFilter.ALL -> items
}

// Display desde campos (misma forma que mapMessage).
fun tgMeta(f: TgFile): String {
    val size = formatBytes(f.sizeBytes)
    val date = if (f.dateSec > 0) fmtDate(f.dateSec) else ""
    return listOfNotNull(
        f.artist.ifEmpty { null },
        f.dur.ifEmpty { null },
        size,
        date.ifEmpty { null }
    ).joinToString(" · ")
}
