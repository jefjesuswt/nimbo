package com.nimbo.feature.cloud

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nimbo.R
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.data.cloud.CloudOps
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.PlaybackPositions
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.isTextFile
import com.nimbo.data.cloud.normalizePath
import com.nimbo.data.cloud.parentOf
import com.nimbo.data.cloud.showTitle
import com.nimbo.data.transfer.FileFetcher
import com.nimbo.data.transfer.TransferManager
import com.nimbo.player.PlayerController
import com.nimbo.ui.common.Snack
import com.nimbo.ui.common.UiText
import com.nimbo.ui.common.text
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ClipMode { CUT, COPY }

data class Clip(val mode: ClipMode, val files: List<TgFile>)

// Portapapeles compartido entre pestañas: cortas en Música, pegas en
// Archivos. Vive lo que la Activity (sobrevive a rotar).
@ActivityRetainedScoped
class CloudClipboard @Inject constructor() {
    val clip = MutableStateFlow<Clip?>(null)
}

// Lo que la pantalla tiene que hacer fuera del VM (navegar, abrir apps).
sealed interface CloudEvent {
    data class Message(val snack: Snack) : CloudEvent
    data class OpenPhotos(val ids: List<Long>, val index: Int) : CloudEvent
    data class OpenVideo(val messageId: Long) : CloudEvent
    data class OpenReader(val messageId: Long) : CloudEvent
    data object OpenPlayer : CloudEvent
    data class OpenSystem(val path: String, val mime: String, val title: String) : CloudEvent
    data class OpenInTelegram(val userId: Long, val messageId: Long) : CloudEvent
}

// Una instancia por pestaña (Archivos / Música / Fotos).
@HiltViewModel(assistedFactory = CloudViewModel.Factory::class)
class CloudViewModel @AssistedInject constructor(
    @Assisted val tab: CloudTab,
    private val repo: CloudRepository,
    private val ops: CloudOps,
    private val transfers: TransferManager,
    private val fetcher: FileFetcher,
    private val player: PlayerController,
    private val prefs: AppPrefs,
    private val clipboard: CloudClipboard,
    positions: PlaybackPositions
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(tab: CloudTab): CloudViewModel
    }

    val files: StateFlow<List<TgFile>> = repo.files
    val keeps: StateFlow<Set<String>> = repo.keeps
    val loading: StateFlow<Boolean> = repo.loading
    val positions: StateFlow<Map<Long, Long>> = positions.positions
    val clip: StateFlow<Clip?> = clipboard.clip

    private val _browse = MutableStateFlow(Browse(sort = prefs.sort(tab)))
    val browse: StateFlow<Browse> = _browse

    // Selección para cortar/copiar/borrar (ids).
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private val _grid = MutableStateFlow(tab == CloudTab.PHOTOS)
    val grid: StateFlow<Boolean> = _grid

    private val searchIndex = combine(repo.files, repo.keeps) { all, k -> SearchIndex(all, tabFiles(tab, all), k) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchIndex(emptyList(), null, emptySet()))

    val listing: StateFlow<Listing> = combine(repo.files, repo.keeps, _browse, searchIndex) { all, k, b, idx ->
        buildListing(tab, all, k, b, idx)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Listing())

    val folders: StateFlow<List<String>> = repo.files
        .map { all -> all.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _events = Channel<CloudEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun emit(e: CloudEvent) {
        _events.trySend(e)
    }

    private fun say(t: UiText) = emit(CloudEvent.Message(Snack(t)))

    // ---- Navegación ----

    // Abrir carpeta (también desde la búsqueda: navega y la cierra).
    fun openDir(dir: String) = _browse.update { it.copy(dir = dir, query = "", searchOpen = false) }
    fun openAlbum(folder: String?) = _browse.update { it.copy(album = folder) }
    fun openSearch() = _browse.update { it.copy(searchOpen = true) }
    fun closeSearch() = _browse.update { it.copy(searchOpen = false, query = "") }
    // Perder el foco (teclado oculto, scroll): el campo se cierra, el filtro queda.
    fun blurSearch() = _browse.update { it.copy(searchOpen = false) }
    fun setQuery(q: String) = _browse.update { it.copy(query = q) }
    fun setFilter(f: KindFilter) = _browse.update { it.copy(filter = f) }
    fun setGrid(on: Boolean) {
        _grid.value = on
    }

    fun setSort(k: SortKey) {
        _browse.update { it.copy(sort = k) }
        prefs.setSort(tab, k)
    }

    // Back dentro de la pestaña, por prioridad: selección → búsqueda →
    // álbum → carpeta padre. false = nada que deshacer (navega fuera).
    val canGoBack: StateFlow<Boolean> = combine(_browse, _selection) { b, sel ->
        sel.isNotEmpty() || b.searchOpen || b.query.isNotEmpty() || b.album != null || b.dir.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun back() {
        val b = _browse.value
        when {
            _selection.value.isNotEmpty() -> _selection.value = emptySet()
            b.searchOpen || b.query.isNotEmpty() -> closeSearch()
            b.album != null -> openAlbum(null)
            b.dir.isNotEmpty() -> _browse.update { it.copy(dir = parentOf(it.dir)) }
        }
    }

    fun refresh() = repo.refresh()
    fun refreshIfStale() = repo.refreshIfStale()
    suspend fun reload() = repo.reload()

    // ---- Abrir ----

    // Tocar / botón play: audio suena, vídeo y texto abren su visor.
    fun play(f: TgFile) = when {
        f.kind == TgKind.VIDEO -> emit(CloudEvent.OpenVideo(f.messageId))
        f.kind == TgKind.AUDIO -> {
            val q = playQueue(tab, repo.files.value, _browse.value.album, f)
            player.play(q, q.indexOfFirst { it.id == f.id }.coerceAtLeast(0))
            emit(CloudEvent.OpenPlayer)
        }
        f.kind == TgKind.DOC && isTextFile(f.name) -> emit(CloudEvent.OpenReader(f.messageId))
        else -> say(text(R.string.streaming_name, f.name))
    }

    // Tocar foto/vídeo: galería propia; el resto, visor del sistema.
    fun preview(f: TgFile) {
        when {
            f.kind == TgKind.IMAGE -> {
                val b = _browse.value
                val q = photoQueue(tab, repo.files.value, b.dir, b.album, f)
                emit(CloudEvent.OpenPhotos(q.map { it.messageId }, q.indexOfFirst { it.id == f.id }.coerceAtLeast(0)))
            }
            f.kind == TgKind.DOC && isTextFile(f.name) -> emit(CloudEvent.OpenReader(f.messageId))
            f.kind == TgKind.VIDEO -> emit(CloudEvent.OpenVideo(f.messageId))
            else -> viewModelScope.launch {
                val path = fetcher.download(f.fileId)
                val mime = when (f.kind) {
                    TgKind.IMAGE -> "image/*"
                    TgKind.VIDEO -> "video/*"
                    else -> "*/*"
                }
                if (path != null) emit(CloudEvent.OpenSystem(path, mime, f.showTitle()))
                else say(text(R.string.couldnt_open, f.showTitle()))
            }
        }
    }

    fun openInTelegram(f: TgFile) = emit(CloudEvent.OpenInTelegram(repo.myUserId, f.messageId))

    // ---- Selección / portapapeles ----

    fun toggle(f: TgFile) = _selection.update { if (f.id in it) it - f.id else it + f.id }
    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selected(): List<TgFile> {
        val ids = _selection.value
        return repo.files.value.filter { it.id in ids }
    }

    fun cutSelection() {
        clipboard.clip.value = Clip(ClipMode.CUT, selected())
        clearSelection()
        say(text(R.string.cut_hint))
    }

    fun copySelection() {
        clipboard.clip.value = Clip(ClipMode.COPY, selected())
        clearSelection()
        say(text(R.string.copied_hint))
    }

    fun deleteSelection() {
        delete(selected())
        clearSelection()
    }

    fun paste() {
        val c = clipboard.clip.value ?: return
        clipboard.clip.value = null
        val dest = _browse.value.dir
        viewModelScope.launch {
            val total = c.files.size
            if (c.mode == ClipMode.CUT) {
                val n = ops.move(c.files, dest)
                say(if (n == total) text(R.string.moved) else text(R.string.moved_partial, n, total))
            } else {
                val n = ops.copy(c.files, dest)
                say(if (n == total) text(R.string.copied) else text(R.string.copied_partial, n, total))
            }
        }
    }

    // ---- Gestión ----

    // Borrar = ocultar + snackbar "Deshacer" (9b).
    fun delete(files: List<TgFile>) {
        if (files.isEmpty()) return
        val token = repo.deleteWithUndo(files)
        val msg = if (files.size == 1) text(R.string.deleted_one, files[0].showTitle())
        else UiText.Plural(R.plurals.deleted_n, files.size)
        emit(CloudEvent.Message(Snack(msg, text(R.string.undo)) { repo.undoDelete(token) }))
    }

    fun renameTitle(f: TgFile, title: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ops.renameTitle(f, title)
            say(text(if (ok) R.string.renamed else R.string.failed))
            done(ok)
        }
    }

    fun createFolder(name: String) {
        val dir = _browse.value.dir
        val target = if (dir.isEmpty()) name else "$dir/$name"
        viewModelScope.launch {
            if (ops.createFolder(target)) openDir(normalizePath(target))
            else say(text(R.string.folder_create_failed))
        }
    }

    fun renameFolder(dir: String, path: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ops.renameFolder(dir, path)
            say(text(if (ok) R.string.folder_moved else R.string.failed))
            done(ok)
        }
    }

    fun deleteFolder(dir: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ops.deleteFolder(dir)
            say(text(if (ok) R.string.folder_deleted else R.string.failed))
            done(ok)
        }
    }

    fun download(f: TgFile) {
        viewModelScope.launch {
            say(text(if (transfers.download(f)) R.string.saved_to_downloads else R.string.download_failed))
        }
    }

    fun downloadFolder(dir: String) = transfers.downloadFolder(dir)

    // ---- Subir ----

    fun upload(uris: List<Uri>) = transfers.enqueue(uris, _browse.value.dir)
    fun uploadFolder(tree: Uri) = transfers.enqueueFolder(tree, _browse.value.dir)
}
