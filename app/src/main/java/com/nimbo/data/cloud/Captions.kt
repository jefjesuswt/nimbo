package com.nimbo.data.cloud

import com.nimbo.core.util.fmtDur
import org.drinkless.tdlib.TdApi

// Contrato en la nube (AGENTS.md › Formato en la nube): no romper.

// Carpeta = ruta en caption: `#nimbo/music/albums/x`. Verdad en la nube,
// SQLite solo indexa. Puras, testeables.
const val TgTag = "#nimbo/"
// Subidas hechas cuando esto vivía dentro de Toolbox: se leen igual y
// pasan a #nimbo/ al primer retag (mover/renombrar).
const val TgLegacyTag = "#toolbox/"

private fun isFolderTag(w: String) = w.startsWith(TgTag) || w.startsWith(TgLegacyTag)

fun normalizePath(p: String): String =
    p.trim().replace('\\', '/').split('/').map { it.trim() }
        .filter { it.isNotEmpty() && it != "." }.joinToString("/")

fun parseFolder(caption: String): String {
    val tag = caption.split(Regex("\\s+")).firstOrNull(::isFolderTag)
        ?: return ""
    return normalizePath(tag.removePrefix(TgTag).removePrefix(TgLegacyTag))
}

fun tagFolder(caption: String, folder: String): String {
    val clean = caption.split(Regex("\\s+")).filterNot(::isFolderTag)
        .joinToString(" ").trim()
    val f = normalizePath(folder)
    if (f.isEmpty()) return clean
    return if (clean.isEmpty()) "$TgTag$f" else "$clean $TgTag$f"
}

// Metadatos audio en caption (los docs subidos no traen de Telegram):
// `#tgmeta 3723s "Titulo" "Artista"`. Comillas internas se aplanan.
const val TgMetaTag = "#tgmeta"

fun tagMeta(caption: String, title: String, artist: String, durSec: Int): String {
    val clean = Regex("$TgMetaTag \\d+s \".*?\" \".*?\"").replace(caption, "").trim()
    if (durSec <= 0 && title.isEmpty() && artist.isEmpty()) return clean
    val t = title.replace('"', '\'').take(120)
    val a = artist.replace('"', '\'').take(120)
    val tag = "$TgMetaTag ${durSec.coerceAtLeast(0)}s \"$t\" \"$a\""
    return if (clean.isEmpty()) tag else "$clean $tag"
}

fun parseMeta(caption: String): Triple<String, String, Int> {
    val m = Regex("$TgMetaTag (\\d+)s \"(.*?)\" \"(.*?)\"").find(caption)
        ?: return Triple("", "", 0)
    return Triple(m.groupValues[2], m.groupValues[3], m.groupValues[1].toIntOrNull() ?: 0)
}

// Cover de carpeta: `#tgcover *` en la imagen. Presta su fileId a los
// audios de esa misma carpeta (sin herencia a subcarpetas).
const val TgCoverTag = "#tgcover"

fun tagCoverLink(caption: String): String {
    val clean = Regex("$TgCoverTag .*?(?= #|$)").replace(caption, "").trim()
    return if (clean.isEmpty()) "$TgCoverTag *" else "$clean $TgCoverTag *"
}

fun parseCoverLink(caption: String): String {
    val m = Regex("$TgCoverTag (.+?)(?= #[a-z]|$)").find(caption) ?: return ""
    return m.groupValues[1].trim()
}

// Cover generada (extraída de un audio, no era archivo): sigue oculta.
// La cover de archivo real SÍ se lista (se puede ver) y presta thumb igual.
const val TgGenTag = "#tggen"

fun isGenCover(caption: String): Boolean =
    caption.split(Regex("\\s+")).any { it == TgGenTag }

// Nombre original del archivo-cover (las fotos TG no guardan nombre).
const val TgNameTag = "#tgname"

fun tagCoverName(caption: String, name: String): String {
    val clean = Regex("$TgNameTag \".*?\"").replace(caption, "").trim()
    val n = name.replace('"', '\'').take(120)
    if (n.isEmpty()) return clean
    return if (clean.isEmpty()) "$TgNameTag \"$n\"" else "$clean $TgNameTag \"$n\""
}

fun parseCoverName(caption: String): String {
    val m = Regex("$TgNameTag \"(.*?)\"").find(caption) ?: return ""
    return m.groupValues[1]
}

// Única cover de carpeta válida: la embebida en el audio (#tggen) o un
// archivo con nombre canónico (cover.jpg, folder.png…). Links viejos de
// otro tipo (por nombre de pista, imagen suelta) se ignoran: la imagen
// queda como archivo normal.
fun isFolderCover(caption: String): Boolean =
    parseCoverLink(caption) == "*" && (isGenCover(caption) || isCoverName(parseCoverName(caption)))

// Carpeta virtual vacía: marcador de texto `#tgkeep` (los mensajes de
// texto no se listan como archivos, pero la carpeta sí navega).
const val TgKeepTag = "#tgkeep"

fun isKeepMark(text: String): Boolean =
    text.split(Regex("\\s+")).any { it == TgKeepTag }

// Nombres típicos de carátula en un dir (minúsculas).
val CoverNames = setOf(
    "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
    "folder.jpg", "folder.jpeg", "folder.png",
    "front.jpg", "front.jpeg", "front.png",
    "albumart.jpg", "albumart.png", "album.jpg", "album.png",
    "artwork.jpg", "artwork.png", "thumb.jpg", "thumb.png"
)

fun isCoverName(name: String): Boolean = name.lowercase() in CoverNames

private val AudioExts = setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")
private val ImageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")

fun isAudioName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in AudioExts

fun isImageName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in ImageExts

// Traducción guardada (archivo hermano): `#tgtrans`.
const val TgTransTag = "#tgtrans"

fun isTranslationMark(text: String): Boolean =
    text.split(Regex("\\s+")).any { it == TgTransTag }

// Hermano traducido: "05_x.txt" + "es" → "05_x.es.txt".
fun siblingName(name: String, tgt: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) + ".$tgt" + name.substring(dot)
    else "$name.$tgt"
}

// Troceo (spec 13): archivo > límite de Telegram partido en
// `nombre.ext.001`, `.002`… (formato split de 7-Zip) con
// `#part 1/3 <grupo>`. Nimbo los junta en uno; fuera se unen con 7-Zip.
const val TgPartTag = "#part"

data class PartTag(val index: Int, val count: Int, val group: String)

fun tagPart(caption: String, p: PartTag): String =
    "$caption $TgPartTag ${p.index}/${p.count} ${p.group}".trim()

fun parsePart(caption: String): PartTag? {
    val m = Regex("$TgPartTag (\\d+)/(\\d+) (\\w+)").find(caption) ?: return null
    val i = m.groupValues[1].toIntOrNull() ?: return null
    val n = m.groupValues[2].toIntOrNull() ?: return null
    return if (i in 1..n) PartTag(i, n, m.groupValues[3]) else null
}

fun partName(name: String, index: Int): String = "$name.${index.toString().padStart(3, '0')}"

fun partBaseName(name: String): String = name.replace(Regex("\\.\\d{3}$"), "")

// Cifrado (spec 12): el archivo viaja como `<azar>.nimbo` (formato en
// data/crypto/EncFormat.kt) con `#enc <info>`: nombre real, tamaño y
// #tgmeta cifrados con la clave maestra. La carpeta sí va en claro.
const val TgEncTag = "#enc"

fun tagEnc(caption: String, info: String): String = "$caption $TgEncTag $info".trim()

fun parseEnc(caption: String): String =
    Regex("$TgEncTag ([A-Za-z0-9_-]+)").find(caption)?.groupValues?.get(1).orEmpty()

// Clave maestra envuelta con la contraseña, en un texto de Saved Messages
// (sirve en otro teléfono): `#tgkey 1 <iteraciones> <sal> <envuelta>`.
const val TgKeyTag = "#tgkey"

data class KeyText(val iters: Int, val salt: String, val wrapped: String)

fun keyText(k: KeyText): String = "$TgKeyTag 1 ${k.iters} ${k.salt} ${k.wrapped}"

fun parseKeyText(text: String): KeyText? {
    val m = Regex("$TgKeyTag 1 (\\d+) ([A-Za-z0-9_-]+) ([A-Za-z0-9_-]+)").find(text) ?: return null
    return KeyText(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2], m.groupValues[3])
}

fun kindOfName(name: String): TgKind = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3", "m4a", "ogg", "opus", "flac", "wav", "aac" -> TgKind.AUDIO
    "zip", "7z", "tar", "rar", "gz", "xz" -> TgKind.ARCHIVE
    "jpg", "jpeg", "png", "gif", "webp", "heic" -> TgKind.IMAGE
    "mp4", "mkv", "mov", "webm" -> TgKind.VIDEO
    else -> TgKind.DOC
}

// Audios sin arte embebido toman la cover de su carpeta (folder → fileId).
// Solo carpeta exacta: nada de heredar de padres ni subcarpetas.
fun applyCoverLinks(files: List<TgFile>, links: Map<String, Int>): List<TgFile> =
    files.map { f ->
        val id = if (f.kind == TgKind.AUDIO && f.thumbFileId == 0) links[f.folder] else null
        if (id != null && id != 0) f.copy(thumbFileId = id) else f
    }

// Mapeo puro mensaje → archivo (testeable sin TDLib corriendo).
fun mapMessage(m: TdApi.Message): TgFile? {
    val caption = when (val c = m.content) {
        is TdApi.MessageAudio -> c.caption?.text.orEmpty()
        is TdApi.MessageDocument -> c.caption?.text.orEmpty()
        is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
        is TdApi.MessageVideo -> c.caption?.text.orEmpty()
        else -> return null
    }
    val folder = parseFolder(caption)
    val (mTitle, mArtist, mDur) = parseMeta(caption)
    return when (val c = m.content) {
        is TdApi.MessageAudio -> {
            val a = c.audio
            val name = a.fileName.ifEmpty { a.title.ifEmpty { mTitle.ifEmpty { "audio" } } }
            val ds = if (a.duration > 0) a.duration else mDur
            val dur = if (ds > 0) fmtDur(ds) else ""
            TgFile(
                id = m.id.toString(), name = name, kind = TgKind.AUDIO,
                meta = "", sizeBytes = a.audio.size,
                messageId = m.id, fileId = a.audio.id,
                remoteId = a.audio.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date, dur = dur, durSec = ds,
                title = a.title.ifEmpty { mTitle }, artist = a.performer.ifEmpty { mArtist },
                thumbFileId = a.albumCoverThumbnail?.file?.id ?: 0,
                thumbMini = a.albumCoverMinithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessagePhoto -> {
            val cap = c.caption?.text.orEmpty()
            // Cover generada: oculta (presta thumb, no era archivo).
            // Cover de archivo real: visible (se puede ver) + presta thumb.
            if (parseCoverLink(cap).isNotEmpty() && isGenCover(cap)) return null
            val best = c.photo.sizes.maxByOrNull { it.width * it.height }
            val f = best?.photo
            TgFile(
                id = m.id.toString(), name = parseCoverName(cap).ifEmpty { "photo-${m.id}.jpg" }, kind = TgKind.IMAGE,
                meta = "", sizeBytes = f?.size ?: 0,
                messageId = m.id, fileId = f?.id ?: 0,
                remoteId = f?.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date,
                thumbFileId = f?.id ?: 0,
                thumbMini = c.photo.minithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessageVideo -> {
            val v = c.video
            val name = v.fileName.ifEmpty { "video-${m.id}.mp4" }
            val dur = fmtDur(v.duration)
            TgFile(
                id = m.id.toString(), name = name, kind = TgKind.VIDEO,
                meta = "", sizeBytes = v.video.size,
                messageId = m.id, fileId = v.video.id,
                remoteId = v.video.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date, dur = dur, durSec = v.duration,
                thumbFileId = v.thumbnail?.file?.id ?: 0,
                thumbMini = v.minithumbnail?.data ?: byteArrayOf()
            ).let { it.copy(meta = tgMeta(it)) }
        }
        is TdApi.MessageDocument -> {
            val caption = c.caption?.text.orEmpty()
            // Cover generada como documento: oculta (no era archivo).
            if (parseCoverLink(caption).isNotEmpty() && isGenCover(caption)) return null
            val d = c.document
            val part = parsePart(caption)
            val name = d.fileName.ifEmpty { "file" }.let { if (part != null) partBaseName(it) else it }
            val kind = kindOfName(name)
            val isAudio = kind == TgKind.AUDIO
            val ds = if (isAudio) mDur else 0
            // Cover-documento: sin thumb del servidor → el propio archivo
            // hace de thumb (la grilla lo baja igual vía fileId).
            val selfThumb = isFolderCover(caption) && (kind == TgKind.IMAGE || kind == TgKind.VIDEO)
            TgFile(
                id = m.id.toString(), name = name, kind = kind,
                meta = "", sizeBytes = d.document.size,
                messageId = m.id, fileId = d.document.id,
                remoteId = d.document.remote?.id.orEmpty(),
                folder = folder, dateSec = m.date,
                dur = if (ds > 0) fmtDur(ds) else "",
                durSec = ds,
                title = if (isAudio) mTitle else "",
                artist = if (isAudio) mArtist else "",
                thumbFileId = if (selfThumb) d.document.id else if (kind == TgKind.IMAGE || kind == TgKind.VIDEO) d.thumbnail?.file?.id ?: 0 else 0,
                thumbMini = if (kind == TgKind.IMAGE || kind == TgKind.VIDEO) d.minithumbnail?.data ?: byteArrayOf() else byteArrayOf(),
                parts = part?.let { listOf(TgPart(it.index, m.id, d.document.id, d.document.size)) }.orEmpty(),
                partCount = part?.count ?: 0,
                partGroup = part?.group.orEmpty(),
                encInfo = parseEnc(caption)
            ).let { it.copy(meta = tgMeta(it)) }
        }
        else -> null
    }
}

// Cover de carpeta → (folder a fileId). Pura, testeable.
// Vale foto o documento (las covers nuevas viajan como documento para
// conservar bytes originales; las viejas como foto siguen valiendo).
fun coverLinkOf(m: TdApi.Message): Pair<String, Int>? {
    val caption = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
        is TdApi.MessageDocument -> c.caption?.text.orEmpty()
        else -> return null
    }
    if (!isFolderCover(caption)) return null
    val fid = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id
        is TdApi.MessageDocument -> c.document.document.id
        else -> null
    } ?: return null
    if (fid == 0) return null
    return parseFolder(caption) to fid
}
