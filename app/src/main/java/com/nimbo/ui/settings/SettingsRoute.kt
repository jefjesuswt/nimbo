package com.nimbo.ui.settings

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nimbo.ui.common.RadioDialog
import com.nimbo.ui.common.SectionLabel
import com.nimbo.ui.common.NimboScaffold

// s-settings de index.html, filas estilo Mihon (icono plano, sin círculo).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    themeMode: String,
    seed: String,
    dynamic: Boolean,
    onThemeMode: (String) -> Unit,
    onSeed: (String) -> Unit,
    onDynamic: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    fun set(key: String, v: Any) = SettingsPrefs.save(ctx, key, v)
    var themeDlg by remember { mutableStateOf(false) }
    var aboutDlg by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    NimboScaffold(
        title = "Settings",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
        ) {
            item {
                Group("Appearance") {
                    PrefRow(
                        icon = Icons.Filled.Palette, title = "Theme",
                        sub = themeModeLabel(themeMode),
                        onClick = { themeDlg = true }
                    )
                    PrefRow(
                        icon = Icons.Filled.Palette, title = "Dynamic color",
                        sub = "Follow wallpaper (Android 12+)",
                        trailing = { Switch(dynamic, { onDynamic(it); set(SettingsPrefs.DYNAMIC, it) }) }
                    )
                    // Semillas solo sin dynamic: con dynamic manda el wallpaper.
                    if (seedsVisible(dynamic)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Seed color", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Base palette when dynamic color is off",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                            Row(Modifier.padding(top = 12.dp)) {
                                listOf("OCEAN" to "Ocean", "MEADOW" to "Meadow", "DUSK" to "Dusk").forEach { (v, l) ->
                                    val sel = seed == v
                                    Column(
                                        Modifier.weight(1f).clip(MaterialTheme.shapes.large)
                                            .clickable { onSeed(v); set(SettingsPrefs.SEED, v) }
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier.size(56.dp).clip(CircleShape).background(seedColor(v)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (sel) Icon(
                                                Icons.Filled.Check, "Selected",
                                                tint = Color.White, modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Text(l, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
                TelegramGroup()
                Group("Notifications") {
                    PrefRow(
                        icon = Icons.Filled.Tune, title = "Notification categories",
                        sub = "Manage in system settings",
                        trailIcon = Icons.Filled.OpenInNew,
                        onClick = {
                            ctx.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            })
                        }
                    )
                }
                Group("About") {
                    PrefRow(icon = Icons.Filled.Info, title = "About Nimbo", sub = "Version 1.0") {
                        aboutDlg = true
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (themeDlg) {
        RadioDialog(
            title = "Theme",
            options = listOf("system" to "System default", "light" to "Light", "dark" to "Dark"),
            initial = themeMode,
            onDismiss = { themeDlg = false },
            onConfirm = {
                themeDlg = false
                onThemeMode(it); set(SettingsPrefs.THEME_MODE, it)
            }
        )
    }
    if (aboutDlg) {
        AlertDialog(
            onDismissRequest = { aboutDlg = false },
            title = { Text("Nimbo") },
            text = { Text("Version 1.0\nYour files, music and photos in your own Telegram Saved Messages. Not affiliated with Telegram.") },
            confirmButton = { TextButton(onClick = { aboutDlg = false }) { Text("OK") } }
        )
    }
}

private fun seedColor(v: String): Color = when (v) {
    "MEADOW" -> Color(0xFF5A9A5E)
    "DUSK" -> Color(0xFF8B62BF)
    else -> Color(0xFF4A78C2)
}

// Keys TDLib + estado sesión. Keys solo cifradas en celu, nunca en repo.
@Composable
private fun TelegramGroup() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val auth by com.nimbo.ui.telegram.TgClient.auth.collectAsState()
    val saved = remember { com.nimbo.ui.telegram.TgSecrets.load(ctx) }
    var apiId by remember { mutableStateOf(saved.first?.toString() ?: "") }
    var apiHash by remember { mutableStateOf(saved.second) }
    var err by remember { mutableStateOf(false) }
    Group("Telegram") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                when (auth) {
                    com.nimbo.ui.telegram.TgAuthState.READY -> "Connected to Telegram cloud"
                    com.nimbo.ui.telegram.TgAuthState.WAIT_CODE,
                    com.nimbo.ui.telegram.TgAuthState.WAIT_PASSWORD -> "Login in progress…"
                    else -> if (apiId.isNotBlank()) "Keys saved, not connected" else "No API keys yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it.filter { c -> c.isDigit() } },
                label = { Text("API ID") },
                singleLine = true,
                isError = err,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim() },
                label = { Text("API hash") },
                singleLine = true,
                isError = err,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (err) Text(
                "Check both values.",
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    val id = com.nimbo.ui.telegram.parseApiId(apiId)
                    if (id != null && apiHash.isNotBlank()) {
                        err = false
                        com.nimbo.ui.telegram.TgSecrets.save(ctx, id, apiHash)
                        com.nimbo.ui.telegram.TgClient.start(ctx, id, apiHash.trim())
                    } else err = true
                }) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    com.nimbo.ui.telegram.TgClient.logout()
                    com.nimbo.ui.telegram.TgSecrets.clear(ctx)
                    apiId = ""
                    apiHash = ""
                }) { Text("Forget & logout") }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Column(Modifier.padding(horizontal = 8.dp)) { content() }
}

// Fila estilo Mihon: icono plano sin círculo, como M3 Settings.
@Composable
private fun PrefRow(
    icon: ImageVector,
    title: String,
    sub: String,
    trailIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        trailing?.invoke()
        trailIcon?.let { Icon(it, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}
