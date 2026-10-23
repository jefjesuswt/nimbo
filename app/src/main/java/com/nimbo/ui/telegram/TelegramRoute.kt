package com.nimbo.ui.telegram

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimbo.ui.common.EmptyState
import com.nimbo.ui.common.EqBars
import com.nimbo.ui.common.FilterChipRow
import com.nimbo.ui.common.LeadingCircle
import com.nimbo.ui.common.RadioDialog
import com.nimbo.ui.common.NimboScaffold
import com.nimbo.ui.common.fabVisible
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// s-tg de index.html: login → OTP → archivos + minibar. Auth real TDLib (7a),
// archivos aún mock hasta 7b.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TelegramRoute(tab: String = "files") {
    // -1 keys, 0 login, 1 code, 2 files, 3 password 2FA. Auth TDLib manda.
    var view by remember { mutableIntStateOf(-1) }
    val ctx = LocalContext.current
    var hasKeys by remember { mutableStateOf(TgSecrets.hasKeys(ctx)) }
    val auth by TgClient.auth.collectAsState()
    val tgError by TgClient.error.collectAsState()
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(defaultCountry()) }
    var sentTo by remember { mutableStateOf("") }
    var codeErr by remember { mutableStateOf(false) }

    LaunchedEffect(hasKeys) {
        if (hasKeys) {
            val (id, hash) = TgSecrets.load(ctx)
            if (id != null && hash.isNotBlank()) TgClient.start(ctx, id, hash)
        } else view = -1
    }
    LaunchedEffect(auth) {
        if (!hasKeys) return@LaunchedEffect
        view = when (auth) {
            TgAuthState.READY -> 2
            TgAuthState.WAIT_CODE -> 1
            TgAuthState.WAIT_PASSWORD -> 3
            else -> 0
        }
    }
    LaunchedEffect(tgError) { if (tgError != null) codeErr = true }
    var filter by remember { mutableStateOf("all") }
    var grids by remember { mutableStateOf(mapOf("photos" to true)) }
    val grid = grids[tab] ?: false
    fun setGrid(v: Boolean) { grids = grids + (tab to v) }
    // Álbum (carpeta) abierto en Audio/Fotos; null = lista de álbumes.
    var openAlbum by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) { openAlbum = null }
    // Nube real (7b): repo manda, mock fuera.
    val cloud by TgRepo.files.collectAsState()
    // Música: todos los audios por carpeta y título. Fotos: imágenes y vídeos, recientes primero.
    val tabList = remember(cloud, tab) {
        when (tab) {
            "audio" -> cloud.filter { it.kind == TgKind.AUDIO }
                .sortedWith(compareBy({ it.folder.lowercase() }, { it.showTitle().lowercase() }))
            "photos" -> cloud.filter { it.kind == TgKind.IMAGE || it.kind == TgKind.VIDEO }
                .sortedByDescending { it.dateSec }
            else -> null
        }
    }
    val openAlbumName = remember(tabList, openAlbum) {
        albumsOf(tabList.orEmpty()).firstOrNull { it.folder == openAlbum }?.name ?: "Unsorted"
    }
    val keeps by TgRepo.keeps.collectAsState()
    val syncing by TgRepo.loading.collectAsState()
    // Carpeta actual 7f ("music/rock", "" = raíz).
    var dir by remember { mutableStateOf("") }
    // Selección para mover/copiar (sobrevive a la navegación: cortas,
    // navegas, pegas). Clipboard: "cut"/"copy" + archivos.
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<Pair<String, List<TgFile>>?>(null) }
    fun toggleSel(f: TgFile) {
        selection = if (f.id in selection) selection - f.id else selection + f.id
    }
    // Player real 7d: estados del motor, mock fuera.
    val track by TgPlayer.track.collectAsState()
    val playing by TgPlayer.playing.collectAsState()
    val posMs by TgPlayer.posMs.collectAsState()
    val durMs by TgPlayer.durMs.collectAsState()
    val playingId = track?.id
    val playP = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    var sheet by remember { mutableStateOf(false) }
    // State machine sheet⟺minibar.
    LaunchedEffect(sheet) { PlayerNav.setSheet(sheet) }
    fun startService() {
        ctx.startService(android.content.Intent(ctx, PlaybackService::class.java))
    }
    // Cola = reproducibles del álbum actual (raíz del path), no todo.
    fun playFile(f: TgFile) {
        val album = if ('/' in f.folder) f.folder.substringBefore('/') else f.folder
        val prefix = "$album/"
        val q = cloud.filter {
            (it.kind == TgKind.AUDIO || it.kind == TgKind.VIDEO) &&
                (album.isEmpty() || it.folder == album || it.folder.startsWith(prefix))
        }
        val list = (if (tab == "audio" && openAlbum != null) tabList.orEmpty().filter { it.folder == openAlbum } else q)
            .ifEmpty { listOf(f) }
        val idx = list.indexOfFirst { it.id == f.id }.coerceAtLeast(0)
        TgPlayer.init(ctx)
        TgPlayer.play(list, idx)
        startService()
        sheet = true
    }
    // Gestión 7e: archivo o carpeta (path) con sheet abierto.
    var manageFile by remember { mutableStateOf<TgFile?>(null) }
    var manageDir by remember { mutableStateOf<String?>(null) }
    // Visor propio: fotos (pager) + lector texto.
    var viewerList by remember { mutableStateOf<List<TgFile>>(emptyList()) }
    var viewerIdx by remember { mutableStateOf<Int?>(null) }
    var textFile by remember { mutableStateOf<TgFile?>(null) }
    // Overlays primero: el back cierra el visor/lector, no navega detrás.
    // (El handler de carpeta, registrado antes, cede ante estos.)
    androidx.activity.compose.BackHandler(enabled = viewerIdx != null) { viewerIdx = null }
    androidx.activity.compose.BackHandler(enabled = textFile != null) { textFile = null }
    // Selección activa: el back la limpia antes de navegar.
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    // Búsqueda (lupa del topbar): filtra todo el árbol mientras escribes.
    var searching by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun closeSearch() {
        searching = false
        query = ""
    }
    androidx.activity.compose.BackHandler(
        enabled = (searching || query.isNotEmpty()) && viewerIdx == null && textFile == null && !sheet
    ) { closeSearch() }
    androidx.activity.compose.BackHandler(
        enabled = openAlbum != null && !searching && query.isEmpty() && viewerIdx == null && textFile == null && !sheet
    ) { openAlbum = null }
    // Carpeta al último: si hay player/visor/lector/diálogo/búsqueda, el back no navega.
    androidx.activity.compose.BackHandler(
        enabled = view == 2 && dir.isNotEmpty() && !searching && query.isEmpty() && viewerIdx == null && textFile == null && !sheet && manageFile == null && manageDir == null
    ) {
        dir = parentOf(dir)
    }
    // El visor/lector esconde la navbar (vuelve al cerrar).
    LaunchedEffect(viewerIdx, textFile) { ViewerNav.open = viewerIdx != null || textFile != null }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(auth) {
        if (auth == TgAuthState.READY) {
            TgRepo.init(ctx)
            TgCovers.init(ctx)
            TgPlayer.init(ctx)
            TgRepo.refresh()
        }
    }

    // Auto-refresh al abrir + cada 5 min visible.
    LaunchedEffect(view) {
        while (view == 2) {
            TgRepo.refreshIfStale()
            kotlinx.coroutines.delay(5 * 60 * 1000)
        }
    }

    // Compartido del sistema (ACTION_SEND): sube cuando hay sesión.
    val shared by SharedUpload.uri.collectAsState()
    LaunchedEffect(shared, auth) {
        if (shared != null && auth == TgAuthState.READY) {
            SharedUpload.take()?.let { TgRepo.enqueue(ctx, listOf(it)) }
        }
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) TgRepo.enqueue(ctx, uris, dir)
    }

    // Subir carpeta (7h1): árbol SAF → audios con ruta + cover linkeada.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    tree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            TgRepo.enqueueFolder(ctx, tree, dir)
        }
    }
    // Crear carpeta virtual.
    var newFolder by remember { mutableStateOf(false) }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { androidx.compose.material3.Text("New folder") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { androidx.compose.material3.Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val target = if (dir.isEmpty()) name else "$dir/$name"
                        TgRepo.createFolder(target) { ok ->
                            if (ok) dir = com.nimbo.ui.telegram.normalizePath(target)
                            else scope.launch { snack.showSnackbar("Couldn't create folder") }
                        }
                        newFolder = false
                    }
                ) { androidx.compose.material3.Text("Create") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { newFolder = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val searchField: @Composable () -> Unit = {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { focus.requestFocus() }
        // Ocultar el teclado (back, gesto) también cuenta como perder el foco.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val imeVisible = WindowInsets.isImeVisible
        var imeShown by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (imeVisible) imeShown = true else if (imeShown) focusManager.clearFocus()
        }
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = cs.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) searching = false },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(
                        "Search files and folders",
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                    inner()
                }
            }
        )
    }
    // Scroll en el contenido = perder el foco (el topbar vuelve a su estado).
    val clearOnScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                focusManager.clearFocus()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    NimboScaffold(
        titleContent = if (view == 2 && searching) searchField else null,
        title = if (view != 2) "Nimbo" else if (query.isNotEmpty()) "\u201C$query\u201D" else if (openAlbum != null) openAlbumName else when (tab) {
            "audio" -> "Audio"
            "photos" -> "Photos"
            else -> "Files"
        },
        snack = snack,
        navigationIcon = if (view == 2 && searching) {
            { IconButton(onClick = { closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } }
        } else if (view == 2 && openAlbum != null) {
            { IconButton(onClick = { openAlbum = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        } else null,
        actions = {
            if (selection.isNotEmpty()) {
                val files = cloud.filter { it.id in selection }
                Text(
                    "${selection.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 4.dp)
                )
                IconButton(onClick = {
                    clipboard = "cut" to files
                    selection = emptySet()
                    scope.launch { snack.showSnackbar("Cut — go to a folder and paste") }
                }) { Icon(Icons.Filled.ContentCut, "Cut") }
                IconButton(onClick = {
                    clipboard = "copy" to files
                    selection = emptySet()
                    scope.launch { snack.showSnackbar("Copied — go to a folder and paste") }
                }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                IconButton(onClick = {
                    files.forEach { TgRepo.delete(it) {} }
                    selection = emptySet()
                }) { Icon(Icons.Filled.Delete, "Delete") }
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Clear") }
            } else if (searching) {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            } else {
                if (clipboard != null) {
                    IconButton(onClick = {
                        val (mode, files) = clipboard!!
                        clipboard = null
                        if (mode == "cut") TgRepo.moveFiles(files, dir) { n, total ->
                            scope.launch { snack.showSnackbar(if (n == total) "Moved" else "Moved $n/$total") }
                        } else TgRepo.copyFiles(files, dir) { n, total ->
                            scope.launch { snack.showSnackbar(if (n == total) "Copied" else "Copied $n/$total") }
                        }
                    }) { Icon(Icons.Filled.ContentPaste, "Paste here") }
                }
                if (view == 2 && !searching) {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Filled.Search, "Search", tint = if (query.isNotEmpty()) cs.primary else androidx.compose.material3.LocalContentColor.current)
                    }
                    IconButton(onClick = { setGrid(!grid) }) {
                        Icon(
                            if (grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            if (grid) "List view" else "Grid view"
                        )
                    }
                    if (tab == "files") {
                        IconButton(onClick = { filterSheet = true }) {
                            Icon(Icons.Filled.FilterList, "Filter and display")
                        }
                    }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                        androidx.compose.material3.DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            if (tab == "files") androidx.compose.material3.DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                onClick = { overflow = false; newFolder = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { overflow = false; TgRepo.refresh() }
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (view == 2 && tab == "files") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingActionButton(
                        onClick = { pickFolder.launch(null) },
                        containerColor = cs.secondaryContainer,
                        contentColor = cs.onSecondaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) { Icon(Icons.Filled.DriveFolderUpload, "Upload folder") }
                    ExtendedFloatingActionButton(
                        onClick = { pickUpload.launch(arrayOf("*/*")) },
                        icon = { Icon(Icons.Filled.CloudUpload, null) },
                        text = { Text("Upload") },
                        containerColor = cs.primaryContainer,
                        contentColor = cs.onPrimaryContainer
                    )
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            when (view) {
                -1 -> KeysView(onSaved = { hasKeys = true })
                0 -> LoginView(
                    phone = phone,
                    country = country,
                    onPhone = { phone = it },
                    onCountry = { country = it },
                    onContinue = {
                        codeErr = false
                        sentTo = fullPhone(country.dial, phone)
                        TgClient.sendPhone(sentTo)
                    }
                )
                1 -> CodeView(
                    phone = sentTo.ifEmpty { phone },
                    error = codeErr,
                    verifying = false,
                    onVerify = { code -> codeErr = false; TgClient.sendCode(code) },
                    onWrongNumber = { view = 0 }
                )
                3 -> PasswordView(
                    error = tgError,
                    onConfirm = { TgClient.sendPassword(it) }
                )
                2 -> Column(Modifier.fillMaxSize()) {
                    // Un pending por lote: corriendo arriba, en cola debajo.
                    val batches by TgRepo.batches.collectAsState()
                    var detailId by remember { mutableStateOf<Long?>(null) }
                    batches.firstOrNull { it.running }?.let { if (it.scan) ScanningCard({ detailId = it.id }) else UploadCard(it, { detailId = it.id }) }
                    batches.firstOrNull { it.id == detailId }?.let { BatchDetail(it) { detailId = null } }
                    // Fallidos del lote: aviso con nombres, una vez.
                    val failed by TgRepo.failed.collectAsState()
                    if (failed.isNotEmpty()) {
                        LaunchedEffect(failed) {
                            snack.showSnackbar("Failed (${failed.size}): " + failed.take(3).joinToString() + if (failed.size > 3) "…" else "")
                            TgRepo.clearFailed()
                        }
                    }
                    // Aviso puntual (cancelado...), una vez.
                    val notice by TgRepo.notice.collectAsState()
                    if (notice != null) {
                        LaunchedEffect(notice) {
                            snack.showSnackbar(notice!!)
                            TgRepo.clearNotice()
                        }
                    }
                    // Conflictos: existe → Replace / Keep both / Skip. Fuera = Cancel.
                    val pending by TgRepo.pending.collectAsState()
                    if (pending != null) {
                        val p = pending!!
                        RadioDialog(
                            title = "${p.conflicts.size} already exist: " + p.conflicts.take(3).joinToString { it.name } + if (p.conflicts.size > 3) "…" else "",
                            options = listOf(
                                "replace" to "Replace",
                                "duplicate" to "Keep both",
                                "skip" to "Skip"
                            ),
                            initial = "replace",
                            onDismiss = { TgRepo.resolvePending(null) },
                            onConfirm = {
                                TgRepo.resolvePending(
                                    when (it) {
                                        "duplicate" -> TgRepo.ResolveMode.DUPLICATE
                                        "skip" -> TgRepo.ResolveMode.SKIP
                                        else -> TgRepo.ResolveMode.REPLACE
                                    }
                                )
                            }
                        )
                    }
                    // En cola: máx alto fijo + scroll (no empuja la lista).
                    val queued = batches.filter { !it.running }
                    if (queued.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            queued.forEach { QueuedCard(it) { detailId = it.id } }
                        }
                    }
                    Box(Modifier.weight(1f).nestedScroll(clearOnScroll)) {
                    val albums = if (tabList != null && openAlbum == null && query.isBlank())
                        remember(tabList, tab) { albumsOf(tabList, byDate = tab == "photos") } else null
                    if (albums != null) AlbumsView(albums, audio = tab == "audio", grid = grid, onOpen = { openAlbum = it })
                    else FilesView(
                    all = cloud,
                    flat = if (openAlbum != null && query.isBlank()) tabList?.filter { it.folder == openAlbum } else tabList,
                    compact = tab == "photos",
                    filter = if (tab == "files") filter else "all",
                    dir = dir,
                    // Abrir carpeta desde la búsqueda: navega y cierra la búsqueda.
                    onDir = { dir = it; closeSearch() },
                    query = query,
                    onFilter = { filter = it },
                    grid = grid,
                    onGrid = { setGrid(it) },
                    // Sin parpadeo: con lotes activos la card ya muestra progreso.
                    syncing = syncing && batches.isEmpty(),
                    onPlay = { f ->
                        if (f.kind == TgKind.AUDIO || f.kind == TgKind.VIDEO) playFile(f)
                        else if (f.kind == TgKind.DOC && isTextFile(f.name)) textFile = f
                        else scope.launch { snack.showSnackbar("Streaming ${f.name}") }
                    },
                    playingId = playingId,
                    playing = playing,
                    playP = playP,
                    keeps = keeps,
                    onOpenInTg = { f ->
                        if (!TgRepo.openInTg(ctx, f)) scope.launch { snack.showSnackbar("Telegram app not found") }
                    },
                    onPreview = { f ->
                        when {
                            f.kind == TgKind.IMAGE -> {
                                // El álbum = carpeta raíz (primer segmento): incluye
                                // la raíz del disco y todas sus subcarpetas vecinas.
                                val album = if ('/' in dir) dir.substringBefore('/') else dir
                                val prefix = "$album/"
                                val list = if (tab == "photos") tabList.orEmpty().filter { it.kind == TgKind.IMAGE && (openAlbum == null || it.folder == openAlbum) } else cloud.filter {
                                    it.kind == TgKind.IMAGE &&
                                        (it.folder == album || (album.isNotEmpty() && it.folder.startsWith(prefix)))
                                }
                                val l = if (list.isEmpty()) listOf(f) else list
                                viewerList = l
                                viewerIdx = l.indexOfFirst { it.id == f.id }.coerceAtLeast(0)
                            }
                            f.kind == TgKind.DOC && isTextFile(f.name) -> textFile = f
                            else -> scope.launch {
                                if (!TgView.open(ctx, f)) snack.showSnackbar("Couldn't open ${f.showTitle()}")
                            }
                        }
                    },
                    onManage = { manageFile = it },
                    onManageDir = { manageDir = it },
                    selection = selection,
                    onToggle = { toggleSel(it) }
                    )
                    }
                }
            }
            // Sheets gestión 7e.
            manageFile?.let { f ->
                ManageSheet(
                    f = f,
                    onDismiss = { manageFile = null },
                    onMsg = { m -> scope.launch { snack.showSnackbar(m) } },
                    onSelect = { toggleSel(it); manageFile = null },
                    onDownload = { TgRepo.download(ctx, f) { ok ->
                        scope.launch { snack.showSnackbar(if (ok) "Saved to Download/Nimbo" else "Download failed") }
                    } }
                )
            }
            manageDir?.let { d ->
                FolderSheet(
                    dir = d,
                    count = countRecursive(cloud, d),
                    folders = cloud.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted(),
                    onDismiss = { manageDir = null },
                    onMsg = { m -> scope.launch { snack.showSnackbar(m) } },
                    onDownload = { TgRepo.downloadFolder(ctx, d) { _ -> } }
                )
            }
            if (filterSheet) {
                ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (tab == "files") {
                            Text("Show", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                            FilterChipRow(
                                options = listOf("all" to "All", "audio" to "Audio", "media" to "Media", "other" to "Files"),
                                selected = filter,
                                onSelect = { filter = it }
                            )
                        }
                        Text(
                            tgStats(cloud),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            // Sheet global: se abre desde el minibar (PlayerNav).
            androidx.compose.runtime.DisposableEffect(Unit) {
                PlayerNav.openSheet = { sheet = true }
                onDispose { PlayerNav.openSheet = null }
            }
            LaunchedEffect(view) {
                if (view == 2 && PlayerNav.pending) {
                    PlayerNav.pending = false
                    sheet = true
                }
            }
            // Player full-screen del prototipo (sobre todo, incl. topbar).
        }
    }
    if (sheet && track != null) {
        PlayerSheet(
            onClose = { sheet = false },
            onMsg = { m -> scope.launch { snack.showSnackbar(m) } }
        )
    }
    viewerIdx?.let { idx ->
        PhotoViewer(
            files = viewerList,
            startIndex = idx,
            onClose = { viewerIdx = null },
            onMsg = { m -> scope.launch { snack.showSnackbar(m) } }
        )
    }
    textFile?.let { f ->
        TextReader(
            f = f,
            onClose = { textFile = null },
            onMsg = { m -> scope.launch { snack.showSnackbar(m) } }
        )
    }
    }
}

// Escaneo de carpeta: título + barra indeterminada, sin % ni contador.
@Composable
private fun ScanningCard(onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scanning folder…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// Card upload por lote: título del lote + archivo actual + % global.
// Sin X v1 (ver TgRepo).
@Composable
private fun UploadCard(b: TgRepo.BatchUi, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p = (b.done.toFloat() / b.total.coerceAtLeast(1L)).coerceIn(0f, 1f)
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (b.down) "Downloading" else "Uploading"} ${b.label}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { TgRepo.cancelBatch(b.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Cancel")
                }
            }
            Text(
                "${b.current} · ${b.doneFiles}/${b.totalFiles} · ${(p * 100).toInt()}%",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { p }, amplitude = { 1f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Lote en espera: mismo lenguaje, sin barra.
@Composable
private fun QueuedCard(b: TgRepo.BatchUi, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Queued · ${b.totalFiles} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

// Detalle de cola a pantalla completa: el item pasa a tener el foco.
// Header (%, velocidad, ETA, conteos) + items con estado + Cancel.
@Composable
private fun BatchDetail(b: TgRepo.BatchUi, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember(b.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(b.id) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val p = (b.done.toFloat() / b.total.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val elapsed = ((now - b.startMs) / 1000.0).coerceAtLeast(0.0)
    val speed = if (elapsed > 2 && b.done > 0) b.done / elapsed else 0.0
    val eta = if (speed > 0 && b.done < b.total) ((b.total - b.done) / speed).toLong() else -1L
    val doneN = b.items.count { it.state == 2 }
    val failN = b.items.count { it.state == 3 }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${if (b.down) "Downloading" else "Uploading"} ${b.label}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "$doneN of ${b.totalFiles} done" + if (failN > 0) " · $failN failed" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    if (b.running || b.scan) {
                        TextButton(onClick = { TgRepo.cancelBatch(b.id); onClose() }) { Text("Cancel") }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(p * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            LinearWavyProgressIndicator(progress = { p }, amplitude = { 1f }, modifier = Modifier.fillMaxWidth())
                            Text(
                                if (b.scan) "Scanning…"
                                else if (speed > 0) "${formatBytes(speed.toLong())}/s" + if (eta >= 0) " · ${if (eta < 60) "${eta}s" else "${eta / 60}m${eta % 60}s"} left" else ""
                                else if (b.running) "Starting…"
                                else "Queued",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                    if (b.current.isNotEmpty()) {
                        Text(
                            b.current,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(b.items.size) { i ->
                        val it = b.items[i]
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (it.state) {
                                2 -> Icon(Icons.Filled.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                3 -> Icon(Icons.Filled.Error, null, tint = cs.error, modifier = Modifier.size(20.dp))
                                1 -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                else -> Icon(Icons.Filled.Schedule, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (it.state == 1 && b.currentFrac > 0) "${(b.currentFrac * 100).toInt()}% · ${formatBytes(it.size)}"
                                    else formatBytes(it.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Subheader sticky: breadcrumb + barra de sync, con fondo surface
// (los items pasan por debajo). Filtros y vista viven en la hoja ⚲.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubHeader(
    syncing: Boolean,
    dir: String,
    onDir: (String) -> Unit,
    showCrumb: Boolean
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        // Breadcrumb 7f: raíz + segmentos clicables.
        if (showCrumb) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onDir("") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Home, "Root", tint = cs.primary)
                }
                var acc = ""
                dir.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    Text("/", color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
                    acc = if (acc.isEmpty()) seg else "$acc/$seg"
                    val target = acc
                    androidx.compose.material3.TextButton(onClick = { onDir(target) }) {
                        Text(seg, maxLines = 1)
                    }
                }
            }
        }
        if (syncing) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LoginView(
    phone: String,
    country: TgCountry,
    onPhone: (String) -> Unit,
    onCountry: (TgCountry) -> Unit,
    onContinue: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var countryOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("Connect Telegram", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sign in with your phone number to upload files to your Telegram cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        // Tap abre picker países; readOnly+disabled evita teclado.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().clickable { countryOpen = true }
        ) {
            OutlinedTextField(
                value = "${country.name} (+${country.dial})",
                onValueChange = {},
                enabled = false,
                label = { Text("Country") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { onPhone(it.filter { c -> c.isDigit() || c == '+' || c == ' ' }) },
            label = { Text("Phone number") },
            prefix = { Text("+${country.dial} ") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            supportingText = { Text("We’ll send a login code to your Telegram app.") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = onContinue,
            enabled = isPhoneOk(phone),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue") }
    }
    if (countryOpen) {
        com.nimbo.ui.common.RadioDialog(
            title = "Country",
            options = TgCountries.map { it.name to "${it.name} (+${it.dial})" },
            initial = country.name,
            onDismiss = { countryOpen = false },
            onConfirm = { name ->
                countryOpen = false
                TgCountries.firstOrNull { it.name == name }?.let(onCountry)
            }
        )
    }
}

// Keys una sola vez: my.telegram.org → API development tools.
// Solo viven cifradas en este celu (TgSecrets), nunca en repo.
@Composable
private fun KeysView(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("Telegram API keys", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create them once at my.telegram.org → API development tools. Stored encrypted on this phone only.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter { c -> c.isDigit() } },
            label = { Text("API ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = err,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("API hash") },
            singleLine = true,
            isError = err,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (err) Text(
            "Check both values.",
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = {
                val id = parseApiId(apiId)
                if (id != null && apiHash.isNotBlank()) {
                    TgSecrets.save(ctx, id, apiHash)
                    val (sid, shash) = TgSecrets.load(ctx)
                    if (sid != null) TgClient.start(ctx, sid, shash)
                    onSaved()
                } else err = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save & connect") }
    }
}

@Composable
private fun PasswordView(error: String?, onConfirm: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("Two-step verification", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This account has an extra password.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Password") },
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) Text(
            error,
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = { onConfirm(pw) },
            enabled = pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Unlock") }
    }
}

@Composable
private fun CodeView(
    phone: String,
    error: Boolean,
    verifying: Boolean,
    onVerify: (String) -> Unit,
    onWrongNumber: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val digits = remember { List(5) { mutableStateOf("") } }
    val focus = remember { List(5) { FocusRequester() } }
    val fm = LocalFocusManager.current
    val code = digits.joinToString("") { it.value }
    LaunchedEffect(code) {
        if (code.length == 5 && !verifying) {
            fm.clearFocus()
            onVerify(code)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("Enter the code", style = MaterialTheme.typography.headlineSmall)
        Text(
            "We sent a 5-digit code to your Telegram app for $phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            digits.forEachIndexed { i, st ->
                OutlinedTextField(
                    value = st.value,
                    onValueChange = { v ->
                        val d = v.filter { it.isDigit() }.takeLast(1)
                        st.value = d
                        if (d.isNotEmpty() && i < 4) focus[i + 1].requestFocus()
                        if (d.isEmpty() && i > 0) focus[i - 1].requestFocus()
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error,
                    modifier = Modifier.width(56.dp).focusRequester(focus[i]),
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center)
                )
            }
        }
        if (error) Text(
            "That code isn’t right. Check it and try again.",
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (verifying) Text("Verifying…", color = cs.primary, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Row {
            androidx.compose.material3.TextButton(onClick = {}) { Text("Resend code") }
            androidx.compose.material3.TextButton(onClick = onWrongNumber) { Text("Wrong number?") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilesView(
    all: List<TgFile>,
    filter: String,
    onFilter: (String) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    dir: String,
    onDir: (String) -> Unit,
    onPlay: (TgFile) -> Unit,
    onOpenInTg: (TgFile) -> Unit,
    onPreview: (TgFile) -> Unit = {},
    onManage: (TgFile) -> Unit = {},
    onManageDir: (String) -> Unit = {},
    playingId: String?,
    playing: Boolean,
    playP: Float = 0f,
    syncing: Boolean = false,
    keeps: Set<String> = emptySet(),
    selection: Set<String> = emptySet(),
    onToggle: (TgFile) -> Unit = {},
    query: String = "",
    // Música / Fotos: lista plana sin carpetas; compact = galería densa sin pie.
    flat: List<TgFile>? = null,
    compact: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    // Búsqueda: claves normalizadas una vez por cambio de nube, no por tecla.
    val terms = remember(query) { searchTerms(query) }
    val searching = terms.isNotEmpty()
    val base = flat ?: all
    val keyed = remember(base) { base.map { it to searchKey(it) } }
    val folderKeyed = remember(all, keeps) {
        allFolders(all, keeps).map { it to normSearch(it.substringAfterLast('/')) }
    }
    // 7f: carpetas primero (rutas completas), luego archivos con filtro.
    // Buscando: todo el árbol, no solo el dir actual.
    val (subNames, own) = childrenOf(all, dir, keeps)
    val subs = if (flat != null) emptyList()
        else if (searching) searchIn(folderKeyed, terms)
        else subNames.map { if (dir.isEmpty()) it else "$dir/$it" }
    val files = filterTgFiles(if (searching) searchIn(keyed, terms) else flat ?: own, filter)
    val showCrumb = dir.isNotEmpty() || subs.isNotEmpty()
    fun label(path: String) = if (searching) path else path.substringAfterLast('/')
    fun meta(f: TgFile) = if (searching && f.folder.isNotEmpty()) "${f.folder} · ${f.meta}" else f.meta
    if (subs.isEmpty() && files.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            SubHeader(syncing, dir, onDir, showCrumb && flat == null)
            if (searching) EmptyState(
                icon = Icons.Filled.Search,
                title = "No results",
                body = "Nothing matches \"$query\"."
            ) else if (flat != null) EmptyState(
                icon = Icons.Filled.CloudUpload,
                title = "Nothing here yet",
                body = "Upload from the Files tab."
            ) else EmptyState(
                icon = Icons.Filled.CloudUpload,
                title = "Your cloud is empty",
                body = "Upload audio and other files."
            )
        }
    } else if (!grid) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(dir, query) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            stickyHeader { SubHeader(syncing, dir, onDir, showCrumb && flat == null) }
            items(subs, key = { "d$it" }) { s ->
                FolderRow(
                    name = label(s),
                    count = countRecursive(all, s),
                    onOpen = { onDir(s) },
                    onManage = { onManageDir(s) }
                )
            }
                items(files, key = { it.id }) { f ->
                    val current = f.id == playingId
                    val sel = f.id in selection
                    val open = {
                        if (selection.isNotEmpty()) onToggle(f)
                        else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) onPreview(f)
                        else onPlay(f)
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { open() },
                                onLongClick = { if (selection.isNotEmpty()) onToggle(f) else onManage(f) }
                            )
                            .then(if (sel) Modifier.background(cs.primaryContainer.copy(alpha = 0.35f)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (f.kind == TgKind.AUDIO) {
                            // Cover circular + badge audio; si no, play redondo.
                            // Vale hash embebido o thumb linkeado (herencia de padres).
                            val covers by TgCovers.covers.collectAsState()
                            val thumbs by TgThumbs.maps.collectAsState()
                            val cover = f.coverHash.takeIf { it.isNotEmpty() }?.let { covers[it] }
                                ?: thumbs[f.thumbFileId]
                            LaunchedEffect(f.messageId, f.coverHash) {
                                TgCovers.fetch(f, TgRepo::noteCover)
                            }
                            LaunchedEffect(f.thumbFileId) { TgThumbs.fetch(f.thumbFileId) }
                            if (cover != null) {
                                Image(
                                    cover, null,
                                    Modifier.size(40.dp).clip(CircleShape).clickable { onPlay(f) },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                PlayCircle(current = current, playing = playing) { onPlay(f) }
                            }
                        } else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) {
                            val thumbs by TgThumbs.maps.collectAsState()
                            val bmp = thumbs[f.thumbFileId]
                                ?: remember(f.id) {
                                    f.thumbMini.takeIf { it.isNotEmpty() }?.let(TgThumbs::mini)
                                }
                            LaunchedEffect(f.thumbFileId) { TgThumbs.fetch(f.thumbFileId) }
                            if (bmp != null) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            } else {
                                LeadingCircle(
                                    if (f.kind == TgKind.IMAGE) Icons.Filled.Image
                                    else Icons.Filled.Movie
                                )
                            }
                        } else {
                            LeadingCircle(
                                when (f.kind) {
                                    TgKind.DOC -> Icons.Filled.Description
                                    else -> Icons.Filled.FolderZip
                                }
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.showTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(meta(f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.kind == TgKind.AUDIO) {
                                WaveBars(
                                    seed = f.id,
                                    progress = if (current) playP else 0f,
                                    modifier = Modifier.fillMaxWidth().height(26.dp)
                                )
                            }
                        }
                        if (current) EqBars(playing, cs.primary)
                        IconButton(onClick = { onOpenInTg(f) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in Telegram", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            // Grid sin stickyHeader en foundation 1.7: header fijo arriba
            // (siempre visible = sticky efectivo) + grid scrolleable.
            Column(Modifier.fillMaxSize()) {
                SubHeader(syncing, dir, onDir, showCrumb && flat == null)
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                LaunchedEffect(dir, query) { gridState.scrollToItem(0) }
                // Adaptive: 2 columnas en vertical, 4-5 en horizontal.
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (compact) 110.dp else 160.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 160.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
            ) {
                items(subs, key = { "d$it" }) { s ->
                    val path = s
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.combinedClickable(
                            onClick = { onDir(path) },
                            onLongClick = { onManageDir(path) }
                        )
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            // Carpetas sin carátula: solo icono.
                            Icon(Icons.Filled.Folder, null, modifier = Modifier.size(48.dp), tint = cs.onSecondaryContainer)
                            // Badge carpeta: que se lea como carpeta, no archivo.
                            Box(
                                Modifier.align(Alignment.BottomStart).padding(8.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(cs.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Folder, "Folder",
                                    tint = cs.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label(s), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${countRecursive(all, path)} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSecondaryContainer,
                                    maxLines = 1
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = cs.onSecondaryContainer)
                        }
                    }
                }
                items(files, key = { it.id }) { f ->
                    val current = f.id == playingId
                    val sel = f.id in selection
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
                        shape = MaterialTheme.shapes.large,
                        border = if (sel) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) onToggle(f)
                                else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) onPreview(f)
                                else onPlay(f)
                            },
                            onLongClick = { if (selection.isNotEmpty()) onToggle(f) else onManage(f) }
                        )
                    ) {
                        // Art: thumb real si hay (foto/video/caratula),
                        // si no waveform (audio) o icono.
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).background(cs.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            val thumbs by TgThumbs.maps.collectAsState()
                            val covers by TgCovers.covers.collectAsState()
                            val bmp = thumbs[f.thumbFileId]
                                ?: f.coverHash.takeIf { it.isNotEmpty() }?.let { covers[it] }
                                ?: remember(f.id) {
                                    f.thumbMini.takeIf { it.isNotEmpty() }?.let(TgThumbs::mini)
                                }
                            LaunchedEffect(f.thumbFileId) { TgThumbs.fetch(f.thumbFileId) }
                            LaunchedEffect(f.messageId, f.coverHash) {
                                TgCovers.fetch(f, TgRepo::noteCover)
                            }
                            if (bmp != null) {
                                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else if (f.kind == TgKind.AUDIO) {
                                WaveBars(
                                    seed = f.id,
                                    progress = if (current) playP else 0f,
                                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)
                                )
                            } else {
                                Icon(
                                    when (f.kind) {
                                        TgKind.IMAGE -> Icons.Filled.Image
                                        TgKind.VIDEO -> Icons.Filled.Movie
                                        TgKind.DOC -> Icons.Filled.Description
                                        else -> Icons.Filled.FolderZip
                                    },
                                    null, modifier = Modifier.size(48.dp), tint = cs.onSurfaceVariant
                                )
                            }
                            if (f.kind == TgKind.AUDIO) {
                                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                    PlayCircle(current = current, playing = playing, small = true) { onPlay(f) }
                                }
                            } else if (f.kind == TgKind.VIDEO) {
                                Box(Modifier.align(Alignment.Center)) {
                                    PlayCircle(current = false, playing = false, small = true) { onPreview(f) }
                                }
                            }
                        }
                        if (!compact) Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                // minLines = 2 fija altura: cards uniformes aunque título corte.
                                Text(f.showTitle(), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(meta(f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onOpenInTg(f) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in Telegram", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Fila carpeta 7f: entra al dir. Un solo uso, queda aquí.
@Composable
private fun FolderRow(
    name: String,
    count: Int,
    onOpen: () -> Unit,
    onManage: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onManage)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeadingCircle(Icons.Filled.Folder)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                "$count items",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

// Gestión 7e: long-press → rename/move/download/delete. Un solo uso.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageSheet(
    f: TgFile,
    onDismiss: () -> Unit,
    onMsg: (String) -> Unit,
    onSelect: (TgFile) -> Unit = {},
    onDownload: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf("") }
    var name by remember(f.id) { mutableStateOf(f.showTitle()) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(f.showTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when (mode) {
                "rename" -> {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Text("Display title (file in cloud keeps its name).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { mode = "" }, enabled = !busy) { Text("Cancel") }
                        androidx.compose.material3.Button(onClick = {
                            busy = true
                            TgRepo.renameTitle(f, name.trim()) { ok ->
                                busy = false
                                onMsg(if (ok) "Renamed" else "Failed")
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy && name.isNotBlank()) { Text("Save") }
                    }
                }
                "delete" -> {
                    Text("Delete from your cloud? No undo.", color = cs.error)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { mode = "" }, enabled = !busy) { Text("Cancel") }
                        androidx.compose.material3.Button(onClick = {
                            busy = true
                            TgRepo.delete(f) { ok ->
                                busy = false
                                onMsg(if (ok) "Deleted" else "Failed")
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy) { Text("Delete") }
                    }
                }
                else -> {
                    SheetRow("Rename") { mode = "rename" }
                    SheetRow("Select") { onSelect(f) }
                    SheetRow("Download") { onDownload(); onDismiss() }
                    SheetRow("Delete", red = true) { mode = "delete" }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSheet(
    dir: String,
    count: Int,
    folders: List<String>,
    onDismiss: () -> Unit,
    onMsg: (String) -> Unit,
    onDownload: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf("") }
    var path by remember(dir) { mutableStateOf(dir) }
    var busy by remember { mutableStateOf(false) }
    val title = if (dir.isEmpty()) "Root" else dir.substringAfterLast('/')
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count items · retag, no bytes move", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when (mode) {
                "rename" -> {
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Folder path") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    folders.filter { it != dir }.take(6).forEach { fo ->
                        androidx.compose.material3.TextButton(onClick = { path = fo }, enabled = !busy) { Text(fo) }
                    }
                    Row {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { mode = "" }, enabled = !busy) { Text("Cancel") }
                        androidx.compose.material3.Button(onClick = {
                            busy = true
                            TgRepo.renameFolder(dir, path) { ok ->
                                busy = false
                                onMsg(if (ok) "Folder moved" else "Failed")
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy && path.isNotBlank()) { Text("Save") }
                    }
                }
                "delete" -> {
                    Text("Delete folder and its $count files? No undo.", color = cs.error)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { mode = "" }, enabled = !busy) { Text("Cancel") }
                        androidx.compose.material3.Button(onClick = {
                            busy = true
                            TgRepo.deleteFolder(dir) { ok ->
                                busy = false
                                onMsg(if (ok) "Folder deleted" else "Failed")
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy) { Text("Delete") }
                    }
                }
                else -> {
                    SheetRow("Rename / move") { mode = "rename" }
                    SheetRow("Download folder") { onDownload(); onDismiss() }
                    SheetRow("Delete folder", red = true) { mode = "delete" }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetRow(label: String, red: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (red) cs.error else cs.onSurface
        )
    }
}

// Toggle lista/grid con tints explícitos (un solo uso, queda aquí).

// Play redondo prototipo: filled cuando es el actual, tonal resto.
@Composable
private fun PlayCircle(current: Boolean, playing: Boolean, small: Boolean = false, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = if (small) 32.dp else 40.dp
    Box(
        Modifier.size(s).clip(CircleShape)
            .background(if (current) cs.primary else cs.secondaryContainer)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (current && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            null,
            tint = if (current) cs.onPrimary else cs.onSecondaryContainer,
            modifier = Modifier.size(if (small) 18.dp else 22.dp)
        )
    }
}

// Waveform determinista por archivo (estable entre recomposiciones).
// played tiñe barras hasta progress; resto tenue. Un solo uso.
@Composable
private fun WaveBars(seed: String, progress: Float, modifier: Modifier = Modifier, bars: Int = 32) {
    val heights = remember(seed) {
        val r = kotlin.random.Random(seed.hashCode())
        List(bars) { 0.2f + r.nextFloat() * 0.8f }
    }
    val cs = MaterialTheme.colorScheme
    // SpaceEvenly: barras repartidas en todo el ancho, no apiladas a la izq.
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier.width(2.dp).fillMaxHeight(fraction = h)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (i.toFloat() / bars <= progress && progress > 0f) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    }
}

