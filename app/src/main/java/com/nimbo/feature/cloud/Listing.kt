package com.nimbo.feature.cloud

import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgAlbum
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.albumsOf
import com.nimbo.data.cloud.allFolders
import com.nimbo.data.cloud.childrenOf
import com.nimbo.data.cloud.filterTgFiles
import com.nimbo.data.cloud.normSearch
import com.nimbo.data.cloud.searchIn
import com.nimbo.data.cloud.searchKey
import com.nimbo.data.cloud.searchTerms
import com.nimbo.data.cloud.showTitle
import com.nimbo.data.cloud.sortAlbums
import com.nimbo.data.cloud.sortTg

// Estado de navegación de una pestaña (lo que antes eran ~15 `remember`).
data class Browse(
    val dir: String = "",
    val filter: KindFilter = KindFilter.ALL,
    val sort: SortKey,
    val query: String = "",
    // Campo de búsqueda abierto en el topbar.
    val searchOpen: Boolean = false,
    // Álbum (carpeta) abierto en Audio/Fotos; null = lista de álbumes.
    val album: String? = null
)

enum class EmptyKind { NO_RESULTS, NOTHING_HERE, CLOUD_EMPTY }

data class Listing(
    // Música/Fotos sin álbum abierto ni búsqueda: álbumes en vez de archivos.
    val albums: List<TgAlbum>? = null,
    val subs: List<String> = emptyList(),
    val files: List<TgFile> = emptyList(),
    val searching: Boolean = false,
    val showCrumb: Boolean = false,
    // Lista plana (Música/Fotos): sin carpetas ni migas.
    val flat: Boolean = false,
    val albumName: String = ""
) {
    val empty: EmptyKind?
        get() = when {
            albums != null || subs.isNotEmpty() || files.isNotEmpty() -> null
            searching -> EmptyKind.NO_RESULTS
            flat -> EmptyKind.NOTHING_HERE
            else -> EmptyKind.CLOUD_EMPTY
        }
}

// Lo que ve cada pestaña. Música: audios por carpeta y título. Fotos:
// imágenes y vídeos, recientes primero.
fun tabFiles(tab: CloudTab, all: List<TgFile>): List<TgFile>? = when (tab) {
    CloudTab.AUDIO -> all.filter { it.kind == TgKind.AUDIO }
        .sortedWith(compareBy({ it.folder.lowercase() }, { it.showTitle().lowercase() }))
    CloudTab.PHOTOS -> all.filter { it.kind == TgKind.IMAGE || it.kind == TgKind.VIDEO }
        .sortedByDescending { it.dateSec }
    CloudTab.FILES -> null
}

// Claves de búsqueda normalizadas una vez por cambio de nube, no por tecla.
class SearchIndex(all: List<TgFile>, tabList: List<TgFile>?, keeps: Set<String>) {
    val files: List<Pair<TgFile, String>> = (tabList ?: all).map { it to searchKey(it) }
    val folders: List<Pair<String, String>> = allFolders(all, keeps).map { it to normSearch(it.substringAfterLast('/')) }
}

// Pura, testeable: carpetas primero (rutas completas), luego archivos
// filtrados y ordenados. Buscando: todo el árbol, no solo el dir actual.
fun buildListing(
    tab: CloudTab,
    all: List<TgFile>,
    keeps: Set<String>,
    b: Browse,
    index: SearchIndex = SearchIndex(all, tabFiles(tab, all), keeps)
): Listing {
    val tabList = tabFiles(tab, all)
    val terms = searchTerms(b.query)
    val searching = terms.isNotEmpty()
    if (tabList != null && b.album == null && !searching) {
        return Listing(albums = sortAlbums(albumsOf(tabList), b.sort), flat = true)
    }
    val flat = when {
        tabList == null -> null
        b.album != null && !searching -> tabList.filter { it.folder == b.album }
        else -> tabList
    }
    val albumName = b.album?.let { a -> albumsOf(tabList.orEmpty()).firstOrNull { it.folder == a }?.name.orEmpty() }.orEmpty()
    val (subNames, own) = childrenOf(all, b.dir, keeps)
    val subs = when {
        flat != null -> emptyList()
        searching -> searchIn(index.folders, terms)
        else -> subNames.map { if (b.dir.isEmpty()) it else "${b.dir}/$it" }
    }
    val filter = if (tab == CloudTab.FILES) b.filter else KindFilter.ALL
    val files = sortTg(filterTgFiles(if (searching) searchIn(index.files, terms) else flat ?: own, filter), b.sort)
    return Listing(
        subs = subs,
        files = files,
        searching = searching,
        showCrumb = flat == null && (b.dir.isNotEmpty() || subs.isNotEmpty()),
        flat = flat != null,
        albumName = albumName
    )
}

// Cola al reproducir: Música con álbum abierto = ese álbum; si no, los
// reproducibles del álbum raíz (primer segmento) del archivo.
fun playQueue(tab: CloudTab, all: List<TgFile>, album: String?, f: TgFile): List<TgFile> {
    if (tab == CloudTab.AUDIO && album != null) {
        return tabFiles(tab, all).orEmpty().filter { it.folder == album }.ifEmpty { listOf(f) }
    }
    val root = f.folder.substringBefore('/')
    val prefix = "$root/"
    return all.filter {
        (it.kind == TgKind.AUDIO || it.kind == TgKind.VIDEO) &&
            (root.isEmpty() || it.folder == root || it.folder.startsWith(prefix))
    }.ifEmpty { listOf(f) }
}

// Galería al abrir una foto. Fotos: las del álbum abierto (o todas).
// Archivos: el álbum = carpeta raíz del dir actual, con sus subcarpetas.
fun photoQueue(tab: CloudTab, all: List<TgFile>, dir: String, album: String?, f: TgFile): List<TgFile> {
    val list = if (tab == CloudTab.PHOTOS) {
        tabFiles(tab, all).orEmpty().filter { it.kind == TgKind.IMAGE && (album == null || it.folder == album) }
    } else {
        val root = dir.substringBefore('/')
        val prefix = "$root/"
        all.filter { it.kind == TgKind.IMAGE && (it.folder == root || (root.isNotEmpty() && it.folder.startsWith(prefix))) }
    }
    return list.ifEmpty { listOf(f) }
}
