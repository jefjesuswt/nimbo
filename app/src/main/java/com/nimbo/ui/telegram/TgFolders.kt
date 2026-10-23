package com.nimbo.ui.telegram

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

// Navegación 7f: subcarpetas directas + archivos propios de dir.
// `extra` = carpetas vacías (marcadores) que también navegan.
// Puras, testeables.
fun childrenOf(files: List<TgFile>, dir: String, extra: Set<String> = emptySet()): Pair<List<String>, List<TgFile>> {
    val subs = sortedSetOf<String>()
    val own = mutableListOf<TgFile>()
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    files.forEach { f ->
        if (f.folder == dir) own.add(f)
        else if (dir.isEmpty() || f.folder.startsWith(prefix)) {
            val rest = if (dir.isEmpty()) f.folder else f.folder.removePrefix(prefix)
            val head = rest.substringBefore('/')
            if (head.isNotEmpty()) subs.add(head)
        }
    }
    extra.forEach { ef ->
        if (ef != dir && (dir.isEmpty() || ef.startsWith(prefix))) {
            val rest = if (dir.isEmpty()) ef else ef.removePrefix(prefix)
            val head = rest.substringBefore('/')
            if (head.isNotEmpty()) subs.add(head)
        }
    }
    return subs.toList() to own
}

fun parentOf(dir: String): String =
    if ('/' in dir) dir.substringBeforeLast('/') else ""

fun countRecursive(files: List<TgFile>, dir: String): Int {
    if (dir.isEmpty()) return files.size
    val prefix = "$dir/"
    return files.count { it.folder == dir || it.folder.startsWith(prefix) }
}

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

// Audios sin arte embebido toman la cover de su carpeta (folder → fileId).
// Solo carpeta exacta: nada de heredar de padres ni subcarpetas.
fun applyCoverLinks(files: List<TgFile>, links: Map<String, Int>): List<TgFile> =
    files.map { f ->
        val id = if (f.kind == TgKind.AUDIO && f.thumbFileId == 0) links[f.folder] else null
        if (id != null && id != 0) f.copy(thumbFileId = id) else f
    }

// Álbum = carpeta con archivos de la pestaña (Audio o Fotos), directos.
data class TgAlbum(val folder: String, val files: List<TgFile>, val title: String = "") {
    val name: String get() = title.ifEmpty { folder.substringAfterLast('/').ifEmpty { "Unsorted" } }
    val parent: String get() = parentOf(folder)
    val durSec: Int get() = files.sumOf { it.durSec }
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
    val lastDate: Int get() = files.maxOfOrNull { it.dateSec } ?: 0
}

// Audio: alfabético por carpeta. Fotos: el álbum con la subida más reciente primero.
// Nombres repetidos ("CD1", "Audio"…): el título suma la carpeta padre.
fun albumsOf(files: List<TgFile>, byDate: Boolean = false): List<TgAlbum> {
    val groups = files.groupBy { it.folder }
    val dupes = groups.keys.groupingBy { it.substringAfterLast('/') }.eachCount()
    val all = groups.map { (k, v) ->
        val leaf = k.substringAfterLast('/')
        val parent = parentOf(k).substringAfterLast('/')
        TgAlbum(k, v, if ((dupes[leaf] ?: 0) > 1 && parent.isNotEmpty()) "$parent / $leaf" else "")
    }
    return if (byDate) all.sortedByDescending { it.lastDate } else all.sortedBy { it.folder.lowercase() }
}

// Artista del álbum solo si es uno (el más común con ≥ mitad de pistas).
fun albumArtist(a: TgAlbum): String {
    val top = a.files.map { it.artist }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return ""
    return if (top.value * 2 >= a.files.size) top.key else ""
}

fun albumSubtitle(a: TgAlbum, audio: Boolean): String =
    listOf(if (audio) albumArtist(a) else "", a.parent).filter { it.isNotEmpty() }.joinToString(" · ")

// "12 tracks · 48:22 · 142.0 MB · 14 Oct" / "30 photos · 2 videos · 1.2 GB · 3 Oct".
fun albumInfo(a: TgAlbum, audio: Boolean): String {
    fun n(c: Int, one: String) = "$c $one" + if (c == 1) "" else "s"
    val parts = mutableListOf<String>()
    if (audio) {
        parts += n(a.files.size, "track")
        if (a.durSec > 0) parts += fmtDur(a.durSec)
    } else {
        val vids = a.files.count { it.kind == TgKind.VIDEO }
        if (a.files.size - vids > 0) parts += n(a.files.size - vids, "photo")
        if (vids > 0) parts += n(vids, "video")
    }
    parts += formatBytes(a.sizeBytes)
    if (a.lastDate > 0) parts += fmtDate(a.lastDate)
    return parts.joinToString(" · ")
}

// Búsqueda: sin acentos ni mayúsculas; cada palabra debe aparecer (AND).
fun normSearch(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

fun searchTerms(q: String): List<String> = normSearch(q).split(' ').filter { it.isNotEmpty() }

fun searchKey(f: TgFile): String = normSearch("${f.name} ${f.title} ${f.artist}")

// Todas las rutas de carpeta, intermedias y vacías incluidas.
fun allFolders(files: List<TgFile>, extra: Set<String> = emptySet()): Set<String> {
    val out = sortedSetOf<String>()
    (files.map { it.folder } + extra).forEach { f ->
        var p = f
        while (p.isNotEmpty()) {
            out.add(p)
            p = parentOf(p)
        }
    }
    return out
}

// ponytail: escaneo lineal en memoria sobre claves precalculadas (~2000
// filas, <1ms; más rápido que ir a SQLite por tecla). FTS4 en TgIndex si
// la nube pasa de ~50k archivos.
fun <T> searchIn(keyed: List<Pair<T, String>>, terms: List<String>): List<T> =
    if (terms.isEmpty()) emptyList()
    else keyed.filter { (_, k) -> terms.all { it in k } }.map { it.first }

// "1:12:08" / "48:22".
fun fmtDur(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, s)
    else "%d:%02d".format(java.util.Locale.US, m, s)
}

// "14 Oct". UTC fijo: determinista en tests y suficiente en UI.
fun fmtDate(epochSec: Int): String {
    val d = java.time.Instant.ofEpochSecond(epochSec.toLong())
        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
}
