package com.nimbo.feature.cloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.core.util.formatBytes
import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.SortBy
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.countRecursive
import com.nimbo.feature.auth.AuthFlow
import com.nimbo.feature.auth.AuthStep
import com.nimbo.feature.auth.AuthViewModel
import com.nimbo.feature.player.PlayerViewModel
import com.nimbo.feature.transfer.TransferPanel
import com.nimbo.feature.transfer.TransferViewModel
import com.nimbo.ui.common.FilterChipRow
import com.nimbo.ui.common.NimboScaffold
import com.nimbo.ui.common.openInTelegram
import com.nimbo.ui.common.openWithSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Navegación hacia fuera de la pestaña (la resuelve NimboApp).
class CloudNav(
    val openPhotos: (List<Long>, Int) -> Unit,
    val openVideo: (Long) -> Unit,
    val openReader: (Long) -> Unit,
    val openPlayer: () -> Unit
)

// Una pestaña de la nube: login si no hay sesión; si no, archivos (con
// carpetas) o álbumes (Música / Fotos).
@Composable
fun CloudScreen(tab: CloudTab, nav: CloudNav, auth: AuthViewModel) {
    val step by auth.step.collectAsStateWithLifecycle()
    if (step != AuthStep.Ready) {
        NimboScaffold(title = "Nimbo") { Column(Modifier.fillMaxSize()) { AuthFlow(auth) } }
        return
    }
    val vm = hiltViewModel<CloudViewModel, CloudViewModel.Factory>(key = tab.key) { it.create(tab) }
    CloudContent(vm, nav)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CloudContent(
    vm: CloudViewModel,
    nav: CloudNav,
    playerVm: PlayerViewModel = hiltViewModel(),
    transferVm: TransferViewModel = hiltViewModel()
) {
    val tab = vm.tab
    val ctx = LocalContext.current
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val all by vm.files.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val clip by vm.clip.collectAsStateWithLifecycle()
    val syncing by vm.loading.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val batches by transferVm.batches.collectAsStateWithLifecycle()
    val track by playerVm.player.track.collectAsStateWithLifecycle()
    val playing by playerVm.player.playing.collectAsStateWithLifecycle()
    val posMs by playerVm.player.posMs.collectAsStateWithLifecycle()
    val durMs by playerVm.player.durMs.collectAsStateWithLifecycle()
    val playP = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    var manageFile by remember { mutableStateOf<TgFile?>(null) }
    var manageDir by remember { mutableStateOf<String?>(null) }
    var filterSheet by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var pulled by remember { mutableStateOf(false) }

    // Selección → búsqueda → álbum → carpeta padre; si no, back de la app.
    BackHandler(enabled = canGoBack) { vm.back() }

    // Eventos del VM: navegar, abrir apps, snackbars.
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is CloudEvent.OpenPhotos -> nav.openPhotos(e.ids, e.index)
                is CloudEvent.OpenVideo -> nav.openVideo(e.messageId)
                is CloudEvent.OpenReader -> nav.openReader(e.messageId)
                CloudEvent.OpenPlayer -> nav.openPlayer()
                is CloudEvent.OpenSystem -> if (!openWithSystem(ctx, e.path, e.mime, e.title)) {
                    launch { snack.showSnackbar(res.getString(R.string.couldnt_open, e.title)) }
                }
                is CloudEvent.OpenInTelegram -> if (!openInTelegram(ctx, e.userId, e.messageId)) {
                    launch { snack.showSnackbar(res.getString(R.string.tg_not_found)) }
                }
                is CloudEvent.Message -> launch {
                    val r = snack.showSnackbar(
                        e.snack.text.resolve(res),
                        actionLabel = e.snack.action?.resolve(res),
                        duration = SnackbarDuration.Short
                    )
                    if (r == SnackbarResult.ActionPerformed) e.snack.onAction?.invoke()
                }
            }
        }
    }

    // Auto-refresh al abrir + cada 5 min visible.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshIfStale()
            delay(5 * 60 * 1000)
        }
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    // Subir carpeta (7h1): árbol SAF → archivos con ruta + cover linkeada.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.uploadFolder(tree)
        }
    }

    if (newFolder) NewFolderDialog(onDismiss = { newFolder = false }, onCreate = { newFolder = false; vm.createFolder(it) })

    val focusManager = LocalFocusManager.current
    // Scroll en el contenido = perder el foco (el topbar vuelve a su estado).
    val clearOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    val searchField: @Composable () -> Unit = {
        val focus = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { focus.requestFocus() }
        // Ocultar el teclado (back, gesto) también cuenta como perder el foco.
        val imeVisible = WindowInsets.isImeVisible
        var imeShown by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (imeVisible) imeShown = true else if (imeShown) focusManager.clearFocus()
        }
        BasicTextField(
            value = browse.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) vm.blurSearch() },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (browse.query.isEmpty()) Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                    inner()
                }
            }
        )
    }

    val searching = browse.searchOpen
    NimboScaffold(
        titleContent = if (searching) searchField else null,
        title = when {
            browse.query.isNotEmpty() -> "“${browse.query}”"
            browse.album != null -> listing.albumName.ifEmpty { stringResource(R.string.unsorted) }
            tab == CloudTab.AUDIO -> stringResource(R.string.tab_audio)
            tab == CloudTab.PHOTOS -> stringResource(R.string.tab_photos)
            else -> stringResource(R.string.tab_files)
        },
        snack = snack,
        navigationIcon = when {
            searching -> {
                { IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search)) } }
            }
            browse.album != null -> {
                { IconButton(onClick = { vm.openAlbum(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }
            }
            else -> null
        },
        actions = {
            if (selection.isNotEmpty()) {
                Text("${selection.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 4.dp))
                IconButton(onClick = vm::cutSelection) { Icon(Icons.Filled.ContentCut, stringResource(R.string.cut)) }
                IconButton(onClick = vm::copySelection) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
                IconButton(onClick = vm::deleteSelection) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
            } else if (searching) {
                if (browse.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
            } else {
                if (clip != null) IconButton(onClick = vm::paste) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.paste_here)) }
                IconButton(onClick = vm::openSearch) {
                    Icon(Icons.Filled.Search, stringResource(R.string.search), tint = if (browse.query.isNotEmpty()) cs.primary else LocalContentColor.current)
                }
                IconButton(onClick = { vm.setGrid(!grid) }) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        stringResource(if (grid) R.string.list_view else R.string.grid_view)
                    )
                }
                IconButton(onClick = { filterSheet = true }) { Icon(Icons.Filled.FilterList, stringResource(R.string.filter_sort)) }
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        if (tab == CloudTab.FILES) DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_folder)) },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                            onClick = { overflow = false; newFolder = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.refresh)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { overflow = false; vm.refresh() }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == CloudTab.FILES) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingActionButton(
                        onClick = { pickFolder.launch(null) },
                        containerColor = cs.secondaryContainer,
                        contentColor = cs.onSecondaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) { Icon(Icons.Filled.DriveFolderUpload, stringResource(R.string.upload_folder)) }
                    ExtendedFloatingActionButton(
                        onClick = { pickUpload.launch(arrayOf("*/*")) },
                        icon = { Icon(Icons.Filled.CloudUpload, null) },
                        text = { Text(stringResource(R.string.upload)) },
                        containerColor = cs.primaryContainer,
                        contentColor = cs.onPrimaryContainer
                    )
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            TransferPanel(snack, transferVm)
            PullToRefreshBox(
                isRefreshing = pulled,
                onRefresh = { scope.launch { pulled = true; vm.reload(); pulled = false } },
                modifier = Modifier.weight(1f).nestedScroll(clearOnScroll)
            ) {
                val albums = listing.albums
                if (albums != null) AlbumsView(albums, audio = tab == CloudTab.AUDIO, grid = grid, onOpen = vm::openAlbum)
                else FilesView(
                    listing = listing,
                    all = all,
                    dir = browse.dir,
                    query = browse.query,
                    grid = grid,
                    onDir = vm::openDir,
                    onPlay = vm::play,
                    onOpenInTg = vm::openInTelegram,
                    onPreview = vm::preview,
                    onManage = { manageFile = it },
                    onManageDir = { manageDir = it },
                    playingId = track?.id,
                    playing = playing,
                    playP = playP,
                    positions = positions,
                    // Sin parpadeo: con lotes activos la card ya muestra progreso.
                    syncing = syncing && batches.isEmpty(),
                    selection = selection,
                    onToggle = vm::toggle,
                    compact = tab == CloudTab.PHOTOS,
                    fabSpace = tab == CloudTab.FILES
                )
            }
        }
    }

    // Gestión 7e: archivo o carpeta con sheet abierto.
    manageFile?.let { f ->
        ManageSheet(
            f = f,
            onDismiss = { manageFile = null },
            onRename = { title, done -> vm.renameTitle(f, title, done) },
            onSelect = { vm.toggle(it); manageFile = null },
            onDelete = { vm.delete(listOf(it)) },
            onDownload = { vm.download(f) }
        )
    }
    manageDir?.let { d ->
        val folders by vm.folders.collectAsStateWithLifecycle()
        FolderSheet(
            dir = d,
            count = countRecursive(all, d),
            folders = folders,
            onDismiss = { manageDir = null },
            onRename = { path, done -> vm.renameFolder(d, path, done) },
            onDelete = { done -> vm.deleteFolder(d, done) },
            onDownload = { vm.downloadFolder(d) }
        )
    }
    if (filterSheet) {
        FilterSortSheet(
            tab = tab,
            browse = browse,
            total = pluralStringResource(R.plurals.files_count, all.size, all.size) + " · " + formatBytes(all.sumOf { it.sizeBytes }),
            onFilter = vm::setFilter,
            onSort = vm::setSort,
            onDismiss = { filterSheet = false }
        )
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSortSheet(
    tab: CloudTab,
    browse: Browse,
    total: String,
    onFilter: (KindFilter) -> Unit,
    onSort: (SortKey) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sort = browse.sort
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == CloudTab.FILES) {
                Text(stringResource(R.string.filter_show), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                FilterChipRow(
                    options = listOf(
                        KindFilter.ALL.name to stringResource(R.string.filter_all),
                        KindFilter.AUDIO.name to stringResource(R.string.tab_audio),
                        KindFilter.MEDIA.name to stringResource(R.string.filter_media),
                        KindFilter.OTHER.name to stringResource(R.string.tab_files)
                    ),
                    selected = browse.filter.name,
                    onSelect = { onFilter(KindFilter.valueOf(it)) }
                )
            }
            Text(stringResource(R.string.sort), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            FilterChipRow(
                options = listOf(
                    SortBy.NAME.name to stringResource(R.string.name),
                    SortBy.DATE.name to stringResource(R.string.sort_date),
                    SortBy.SIZE.name to stringResource(R.string.sort_size)
                ) + (if (tab == CloudTab.AUDIO) listOf(SortBy.DUR.name to stringResource(R.string.sort_duration)) else emptyList()),
                selected = sort.by.name,
                onSelect = { onSort(sort.copy(by = SortBy.valueOf(it))) }
            )
            FilterChipRow(
                options = listOf("asc" to stringResource(R.string.ascending), "desc" to stringResource(R.string.descending)),
                selected = if (sort.asc) "asc" else "desc",
                onSelect = { onSort(sort.copy(asc = it == "asc")) }
            )
            Text(
                total,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
