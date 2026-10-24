package com.nimbo.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nimbo.R
import com.nimbo.data.cloud.CloudTab
import com.nimbo.feature.auth.AuthViewModel
import com.nimbo.feature.cloud.CloudNav
import com.nimbo.feature.cloud.CloudScreen
import com.nimbo.feature.player.MiniPlayer
import com.nimbo.feature.player.PlayerSheet
import com.nimbo.feature.player.PlayerViewModel
import com.nimbo.feature.reader.TextReader
import com.nimbo.feature.settings.SettingsScreen
import com.nimbo.feature.video.VideoViewer
import com.nimbo.feature.viewer.PhotoViewer

private data class TabItem(val route: Any, val label: Int, val on: ImageVector, val off: ImageVector)

private val Tabs = listOf(
    TabItem(FilesRoute, R.string.tab_files, Icons.Filled.Folder, Icons.Outlined.Folder),
    TabItem(MusicRoute, R.string.tab_audio, Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    TabItem(PhotosRoute, R.string.tab_photos, Icons.Filled.Photo, Icons.Outlined.Photo),
    TabItem(MoreRoute, R.string.tab_more, Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
)

// Pestaña estilo Mihon: cada una guarda su estado al cambiar (carpeta,
// scroll); back desde cualquiera vuelve a Archivos.
private fun NavHostController.selectTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

// Shell: navbar + minibar abajo, pestañas y visores en un NavHost, y la
// hoja del player encima del contenido (sobre cualquier pestaña).
@Composable
fun NimboApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = dest == null || Tabs.any { dest.hasRoute(it.route::class) }
    // Una sola sesión de login para todas las pestañas.
    val auth: AuthViewModel = hiltViewModel()
    val playerVm: PlayerViewModel = hiltViewModel()
    val track by playerVm.player.track.collectAsStateWithLifecycle()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val cloudNav = remember(nav) {
        CloudNav(
            openPhotos = { ids, i -> nav.navigate(PhotoRoute(ids, i)) },
            openVideo = { nav.navigate(VideoRoute(it)) },
            openReader = { nav.navigate(ReaderRoute(it)) },
            openPlayer = { playerOpen = true }
        )
    }
    Scaffold(
        // Arriba y a los lados: barra de estado + notch en uno (sin sumar dos
        // veces). Abajo manda la navbar.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            // Los visores (foto, vídeo, texto) esconden minibar + navbar.
            if (onTab) Column {
                if (!playerOpen) MiniPlayer(playerVm, onOpenSheet = { playerOpen = true })
                NavigationBar {
                    Tabs.forEach { t ->
                        val sel = dest?.hasRoute(t.route::class) ?: (t.route == FilesRoute)
                        NavigationBarItem(
                            selected = sel,
                            onClick = { nav.selectTab(t.route) },
                            icon = { Icon(if (sel) t.on else t.off, contentDescription = null) },
                            label = { Text(stringResource(t.label)) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            NavHost(nav, startDestination = FilesRoute) {
                composable<FilesRoute> { CloudScreen(CloudTab.FILES, cloudNav, auth) }
                composable<MusicRoute> { CloudScreen(CloudTab.AUDIO, cloudNav, auth) }
                composable<PhotosRoute> { CloudScreen(CloudTab.PHOTOS, cloudNav, auth) }
                composable<MoreRoute> { SettingsScreen() }
                composable<PhotoRoute> { PhotoViewer(hiltViewModel(), onClose = { nav.popBackStack() }) }
                composable<VideoRoute> { VideoViewer(hiltViewModel(), onClose = { nav.popBackStack() }) }
                composable<ReaderRoute> { TextReader(hiltViewModel(), onClose = { nav.popBackStack() }) }
            }
            if (playerOpen && track != null && onTab) PlayerSheet(playerVm, onClose = { playerOpen = false })
        }
    }
}
