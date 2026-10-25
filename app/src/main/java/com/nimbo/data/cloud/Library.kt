package com.nimbo.data.cloud

import com.nimbo.core.util.fmtDate
import com.nimbo.core.util.fmtDur
import com.nimbo.core.util.formatBytes

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

// Álbum = carpeta con archivos de la pestaña (Audio o Fotos), directos.
data class TgAlbum(val folder: String, val files: List<TgFile>, val title: String = "") {
    // Vacío = raíz; la UI lo muestra como "Unsorted" traducido.
    val name: String get() = title.ifEmpty { folder.substringAfterLast('/') }
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

// Cola de la línea de info: "48:22 · 142.0 MB · 14 Oct". Los recuentos
// (pistas, fotos) los pone la UI, traducidos.
fun albumInfo(a: TgAlbum, audio: Boolean): String {
    val parts = mutableListOf<String>()
    if (audio && a.durSec > 0) parts += fmtDur(a.durSec)
    parts += formatBytes(a.sizeBytes)
    if (a.lastDate > 0) parts += fmtDate(a.lastDate)
    return parts.joinToString(" · ")
}

// Orden (spec 9d): por nombre | fecha | tamaño | duración, asc o desc.
// Se guarda como "name:asc" (formato de antes del refactor).
enum class SortBy(val key: String) { NAME("name"), DATE("date"), SIZE("size"), DUR("dur") }

data class SortKey(val by: SortBy, val asc: Boolean) {
    fun encode(): String = "${by.key}:${if (asc) "asc" else "desc"}"

    companion object {
        fun parse(s: String?): SortKey? {
            val by = SortBy.entries.firstOrNull { it.key == s?.substringBefore(':') } ?: return null
            return SortKey(by, !s.orEmpty().endsWith(":desc"))
        }
    }
}

fun defaultSort(tab: CloudTab): SortKey =
    if (tab == CloudTab.AUDIO) SortKey(SortBy.NAME, true) else SortKey(SortBy.DATE, false)

fun sortTg(files: List<TgFile>, key: SortKey): List<TgFile> {
    val c: Comparator<TgFile> = when (key.by) {
        SortBy.DATE -> compareBy { it.dateSec }
        SortBy.SIZE -> compareBy { it.sizeBytes }
        SortBy.DUR -> compareBy { it.durSec }
        SortBy.NAME -> compareBy { it.showTitle().lowercase() }
    }
    return files.sortedWith(if (key.asc) c else c.reversed())
}

fun sortAlbums(albums: List<TgAlbum>, key: SortKey): List<TgAlbum> {
    val c: Comparator<TgAlbum> = when (key.by) {
        SortBy.DATE -> compareBy { it.lastDate }
        SortBy.SIZE -> compareBy { it.sizeBytes }
        SortBy.DUR -> compareBy { it.durSec }
        SortBy.NAME -> compareBy { it.name.lowercase() }
    }
    return albums.sortedWith(if (key.asc) c else c.reversed())
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
