package com.nimbo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.data.transfer.TransferManager
import com.nimbo.feature.main.NimboApp
import com.nimbo.ui.common.Artwork
import com.nimbo.ui.common.LocalArtwork
import com.nimbo.ui.theme.NimboTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Rotar no recrea la Activity (configChanges en el manifest): Compose
// recompone y conserva carpeta, player, visor y selección.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var transfers: TransferManager
    @Inject lateinit var artwork: Artwork

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        enableEdgeToEdge()
        // Notificaciones de progreso (Android 13+ pide permiso en runtime).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val ap by prefs.appearance.collectAsStateWithLifecycle()
            NimboTheme(
                darkTheme = ap.theme.isDark(isSystemInDarkTheme()),
                seed = ap.seed,
                dynamicColor = ap.dynamic
            ) {
                CompositionLocalProvider(LocalArtwork provides artwork) { NimboApp() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    // ACTION_SEND del sistema → backup a Saved Messages en cuanto haya sesión.
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(transfers::enqueueWhenReady)
    }
}
