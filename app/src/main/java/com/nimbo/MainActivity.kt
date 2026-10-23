package com.nimbo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

// Una sola pantalla (la nube) + ajustes: sin NavigationBar.
@Composable
fun NimboApp(
    themeMode: String,
    seed: String,
    dynamic: Boolean,
    onThemeMode: (String) -> Unit,
    onSeed: (String) -> Unit,
    onDynamic: (Boolean) -> Unit
) {
    val nav = rememberNavController()
    Scaffold(
        bottomBar = {
            // El visor de fotos esconde la minibar. Sin navbar, el inset
            // del sistema lo consume esta columna (las pantallas lo asumen).
            if (!ViewerNav.open) {
                Column(Modifier.navigationBarsPadding()) {
                    MiniPlayer(onOpenSheet = {
                        nav.popBackStack("cloud", inclusive = false)
                        PlayerNav.pending = true
                        PlayerNav.openSheet?.invoke()
                    })
                }
            }
        }
    ) { pad ->
        // Horizontal: el notch queda a un lado, no tapa contenido.
        NavHost(nav, startDestination = "cloud", Modifier.padding(pad).displayCutoutPadding()) {
            composable("cloud") {
                TelegramRoute(onSettings = { nav.navigate("settings") })
            }
            composable("settings") {
                SettingsRoute(
                    onBack = { nav.popBackStack() },
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
