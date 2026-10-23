package com.nimbo

import androidx.compose.ui.res.stringResource
import com.nimbo.R
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nimbo.ui.settings.SettingsPrefs
import com.nimbo.ui.settings.SettingsRoute
import com.nimbo.ui.settings.darkThemeFor
import com.nimbo.ui.telegram.MiniPlayer
import com.nimbo.ui.telegram.PlayerNav
import com.nimbo.ui.telegram.SharedUpload
import com.nimbo.ui.telegram.TelegramRoute
import com.nimbo.ui.telegram.ViewerNav
import com.nimbo.ui.theme.NimboTheme
import com.nimbo.ui.theme.Seed

// Rotar no recrea la Activity (configChanges en el manifest):
// Compose recompone y conserva carpeta, player, visor y selección.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        // Copia automática: al abrir, un pase si está activa (restricciones aplican).
        com.nimbo.backup.Backup.runNow(this)
        enableEdgeToEdge()
        // Notificaciones de progreso (Android 13+ pide permiso en runtime).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { NimboRoot() }
    }

    // PiP del vídeo (spec 08): estado para ocultar controles; en < 12 se
    // entra a mano al salir con Home (12+ usa setAutoEnterEnabled).
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.nimbo.ui.telegram.VideoNav.pip = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT in 26..30 && com.nimbo.ui.telegram.VideoNav.open &&
            com.nimbo.ui.telegram.TgPlayer.playing.value
        ) enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    // ACTION_SEND del sistema → backup a Saved Messages (consume TelegramRoute).
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri: android.net.Uri? =
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        if (uri != null) SharedUpload.put(uri)
    }
}

@Composable
fun NimboRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    var themeMode by remember { mutableStateOf(SettingsPrefs.load(ctx)[SettingsPrefs.THEME_MODE] as String) }
    var seedName by remember { mutableStateOf(SettingsPrefs.load(ctx)[SettingsPrefs.SEED] as String) }
    var dynamic by remember { mutableStateOf(SettingsPrefs.load(ctx)[SettingsPrefs.DYNAMIC] as Boolean) }
    NimboTheme(
        darkTheme = darkThemeFor(themeMode, sysDark),
        seed = runCatching { Seed.valueOf(seedName) }.getOrDefault(Seed.OCEAN),
        dynamicColor = dynamic
    ) {
        NimboApp(
            themeMode = themeMode,
            seed = seedName,
            dynamic = dynamic,
            onThemeMode = { themeMode = it },
            onSeed = { seedName = it },
            onDynamic = { dynamic = it }
        )
    }
}

private data class Tab(val id: String, val label: Int, val on: ImageVector, val off: ImageVector)

private val Tabs = listOf(
    Tab("files", R.string.tab_files, Icons.Filled.Folder, Icons.Outlined.Folder),
    Tab("audio", R.string.tab_audio, Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    Tab("photos", R.string.tab_photos, Icons.Filled.Photo, Icons.Outlined.Photo),
    Tab("more", R.string.tab_more, Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
)

// Estilo Mihon: topbar con título de la pestaña + navbar abajo.
// La nube queda siempre compuesta (conserva carpeta, player, subidas);
// "Más" (ajustes) va encima.
@Composable
fun NimboApp(
    themeMode: String,
    seed: String,
    dynamic: Boolean,
    onThemeMode: (String) -> Unit,
    onSeed: (String) -> Unit,
    onDynamic: (Boolean) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf("files") }
    Scaffold(
        // Arriba y a los lados: barra de estado + notch en uno (sin sumar dos
        // veces). Abajo manda la navbar.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            // El visor de fotos esconde minibar + navbar.
            if (!ViewerNav.open) {
                Column {
                    MiniPlayer(onOpenSheet = {
                        if (tab == "more") tab = "files"
                        PlayerNav.pending = true
                        PlayerNav.openSheet?.invoke()
                    })
                    NavigationBar {
                        Tabs.forEach { t ->
                            val sel = tab == t.id
                            NavigationBarItem(
                                selected = sel,
                                onClick = { tab = t.id },
                                icon = { Icon(if (sel) t.on else t.off, contentDescription = null) },
                                label = { Text(stringResource(t.label)) }
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        // Back en otra pestaña vuelve a Archivos. Registrado antes que la
        // nube: sus handlers (player, visor, carpeta) ganan si están activos.
        BackHandler(enabled = tab == "audio" || tab == "photos") { tab = "files" }
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            TelegramRoute(tab = if (tab == "more") "files" else tab)
            if (tab == "more") {
                BackHandler { tab = "files" }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    SettingsRoute(
                        themeMode = themeMode,
                        seed = seed,
                        dynamic = dynamic,
                        onThemeMode = onThemeMode,
                        onSeed = onSeed,
                        onDynamic = onDynamic
                    )
                }
            }
        }
    }
}
