package com.nimbo.ui.settings

import androidx.compose.material.icons.filled.Language
import android.os.LocaleList
import android.os.Build
import android.content.Context
import android.app.LocaleManager
import androidx.compose.ui.res.stringResource
import com.nimbo.R
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CloudUpload
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
import kotlinx.coroutines.launch

// s-settings de index.html, filas estilo Mihon (icono plano, sin círculo).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
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
    var langDlg by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    NimboScaffold(
        title = stringResource(R.string.tab_more)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
        ) {
            item {
                Group(stringResource(R.string.settings_appearance)) {
                    PrefRow(
                        icon = Icons.Filled.Palette, title = stringResource(R.string.settings_theme),
                        sub = stringResource(themeModeLabel(themeMode)),
                        onClick = { themeDlg = true }
                    )
                    PrefRow(
                        icon = Icons.Filled.Palette, title = stringResource(R.string.settings_dynamic),
                        sub = stringResource(R.string.settings_dynamic_sub),
                        trailing = { Switch(dynamic, { onDynamic(it); set(SettingsPrefs.DYNAMIC, it) }) }
                    )
                    // Idioma por app (Android 13+); el sistema recrea la Activity al cambiarlo.
                    if (Build.VERSION.SDK_INT >= 33) PrefRow(
                        icon = Icons.Filled.Language, title = stringResource(R.string.settings_language),
                        sub = Languages.firstOrNull { it.first == appLang(ctx) }?.second ?: stringResource(R.string.theme_system),
                        onClick = { langDlg = true }
                    )
                    // Semillas solo sin dynamic: con dynamic manda el wallpaper.
                    if (seedsVisible(dynamic)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.settings_seed), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_seed_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                            Row(Modifier.padding(top = 12.dp)) {
                                listOf("OCEAN" to stringResource(R.string.seed_ocean), "MEADOW" to stringResource(R.string.seed_meadow), "DUSK" to stringResource(R.string.seed_dusk)).forEach { (v, l) ->
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
                                                Icons.Filled.Check, stringResource(R.string.selected),
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
                BackupGroup()
                Group(stringResource(R.string.settings_notifications)) {
                    PrefRow(
                        icon = Icons.Filled.Tune, title = stringResource(R.string.settings_notif_categories),
                        sub = stringResource(R.string.settings_notif_sub),
                        trailIcon = Icons.Filled.OpenInNew,
                        onClick = {
                            ctx.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            })
                        }
                    )
                }
                Group(stringResource(R.string.settings_about)) {
                    PrefRow(icon = Icons.Filled.Info, title = stringResource(R.string.settings_about_nimbo), sub = stringResource(R.string.version, "1.0")) {
                        aboutDlg = true
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (themeDlg) {
        RadioDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)),
            initial = themeMode,
            onDismiss = { themeDlg = false },
            onConfirm = {
                themeDlg = false
                onThemeMode(it); set(SettingsPrefs.THEME_MODE, it)
            }
        )
    }
    if (langDlg) {
        RadioDialog(
            title = stringResource(R.string.settings_language),
            options = listOf("" to stringResource(R.string.theme_system)) + Languages,
            initial = appLang(ctx),
            onDismiss = { langDlg = false },
            onConfirm = {
                langDlg = false
                setAppLang(ctx, it)
            }
        )
    }
    if (aboutDlg) {
        AlertDialog(
            onDismissRequest = { aboutDlg = false },
            title = { Text("Nimbo") },
            text = { Text(stringResource(R.string.version, "1.0") + "\n" + stringResource(R.string.about_body)) },
            confirmButton = { TextButton(onClick = { aboutDlg = false }) { Text(stringResource(R.string.ok)) } }
        )
    }
}

// Cada idioma con su propio nombre. "" = el del sistema.
private val Languages = listOf(
    "en" to "English", "es" to "Español", "pt" to "Português", "fr" to "Français", "ja" to "日本語"
)

private fun appLang(ctx: Context): String {
    if (Build.VERSION.SDK_INT < 33) return ""
    val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
    return if (l.isEmpty) "" else l[0].language
}

private fun setAppLang(ctx: Context, tag: String) {
    if (Build.VERSION.SDK_INT < 33) return
    ctx.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
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
                    com.nimbo.ui.telegram.TgAuthState.READY -> stringResource(R.string.tg_connected)
                    com.nimbo.ui.telegram.TgAuthState.WAIT_CODE,
                    com.nimbo.ui.telegram.TgAuthState.WAIT_PASSWORD -> stringResource(R.string.tg_login_progress)
                    else -> if (apiId.isNotBlank()) stringResource(R.string.tg_keys_saved) else stringResource(R.string.tg_no_keys)
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
                stringResource(R.string.check_both),
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
                }) { Text(stringResource(R.string.save)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    com.nimbo.ui.telegram.TgClient.logout()
                    com.nimbo.ui.telegram.TgSecrets.clear(ctx)
                    apiId = ""
                    apiHash = ""
                }) { Text(stringResource(R.string.forget_logout)) }
            }
        }
    }
}

// Copia automática (spec 10): carpetas SAF + condiciones + estado. El
// Worker escribe el estado en prefs; se re-lee al cambiar.
@Composable
private fun BackupGroup() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val res = androidx.compose.ui.platform.LocalResources.current
    val prefs = remember { com.nimbo.backup.BackupPrefs.of(ctx) }
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k?.startsWith("backup_") == true) tick++
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick // relee prefs al cambiar
    val on = prefs.getBoolean(com.nimbo.backup.BackupPrefs.ON, false)
    val trees = com.nimbo.backup.BackupPrefs.trees(ctx)
    val names = remember(trees) { trees.associateWith { com.nimbo.backup.Backup.treeName(ctx, it) } }
    fun apply(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        com.nimbo.backup.Backup.schedule(ctx)
    }
    fun addTree(uri: String) {
        apply { putStringSet(com.nimbo.backup.BackupPrefs.TREES, trees + uri) }
        com.nimbo.backup.Backup.runNow(ctx)
    }
    // Primera vez por carpeta: ¿subir también lo que ya hay? (uri, cuántos)
    var ask by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val n = com.nimbo.backup.Backup.scan(ctx, uri.toString()).size
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (n == 0) addTree(uri.toString()) else ask = uri.toString() to n
            }
        }
    }
    Group(stringResource(R.string.backup_title)) {
        PrefRow(
            icon = Icons.Filled.CloudUpload, title = stringResource(R.string.backup_title),
            sub = stringResource(R.string.backup_switch_sub),
            trailing = {
                Switch(on, {
                    apply { putBoolean(com.nimbo.backup.BackupPrefs.ON, it) }
                    if (it) com.nimbo.backup.Backup.runNow(ctx)
                })
            }
        )
        if (on) {
            trees.forEach { tree ->
                val name = names[tree].orEmpty()
                PrefRow(
                    icon = Icons.Filled.Folder, title = name.ifEmpty { tree.substringAfterLast("%3A") },
                    sub = stringResource(R.string.backup_dest, com.nimbo.backup.backupFolder(name)),
                    trailing = {
                        IconButton(onClick = {
                            runCatching {
                                ctx.contentResolver.releasePersistableUriPermission(
                                    android.net.Uri.parse(tree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            apply { putStringSet(com.nimbo.backup.BackupPrefs.TREES, trees - tree) }
                        }) { Icon(Icons.Filled.Close, stringResource(R.string.backup_remove)) }
                    }
                )
            }
            PrefRow(
                icon = Icons.Filled.CreateNewFolder, title = stringResource(R.string.backup_add_folder),
                sub = if (trees.isEmpty()) stringResource(R.string.backup_no_folders) else "",
                onClick = { pick.launch(null) }
            )
            PrefRow(
                icon = Icons.Filled.Wifi, title = stringResource(R.string.backup_wifi),
                sub = stringResource(R.string.backup_wifi_sub),
                trailing = {
                    Switch(prefs.getBoolean(com.nimbo.backup.BackupPrefs.WIFI, true), {
                        apply { putBoolean(com.nimbo.backup.BackupPrefs.WIFI, it) }
                    })
                }
            )
            PrefRow(
                icon = Icons.Filled.BatteryChargingFull, title = stringResource(R.string.backup_charging),
                sub = "",
                trailing = {
                    Switch(prefs.getBoolean(com.nimbo.backup.BackupPrefs.CHARGING, false), {
                        apply { putBoolean(com.nimbo.backup.BackupPrefs.CHARGING, it) }
                    })
                }
            )
            val last = prefs.getLong(com.nimbo.backup.BackupPrefs.LAST, 0L)
            val pending = prefs.getInt(com.nimbo.backup.BackupPrefs.PENDING, 0)
            val err = prefs.getString(com.nimbo.backup.BackupPrefs.ERROR, null)
            val status = listOfNotNull(
                if (last == 0L) res.getString(R.string.backup_never)
                else res.getString(R.string.backup_last, android.text.format.DateUtils.getRelativeTimeSpanString(last).toString()),
                if (pending > 0) res.getQuantityString(R.plurals.backup_pending, pending, pending) else null,
                err?.let { res.getString(R.string.backup_error, it) }
            ).joinToString("\n")
            PrefRow(
                icon = Icons.Filled.Sync, title = stringResource(R.string.backup_now),
                sub = status,
                onClick = {
                    com.nimbo.backup.Backup.runNow(ctx)
                    android.widget.Toast.makeText(
                        ctx,
                        if (com.nimbo.backup.Backup.networkOk(ctx)) R.string.backup_started else R.string.backup_waiting_wifi,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
            // Archivos que fallaron 5 veces: ya no se reintentan solos.
            var gaveUp by remember { mutableStateOf(prefs.getInt(com.nimbo.backup.BackupPrefs.GAVE_UP, 0)) }
            if (gaveUp > 0) PrefRow(
                icon = Icons.Filled.ErrorOutline,
                title = res.getQuantityString(R.plurals.backup_gave_up, gaveUp, gaveUp),
                sub = stringResource(R.string.backup_retry_sub),
                onClick = {
                    com.nimbo.backup.BackupLog(ctx).resetFails()
                    prefs.edit().putInt(com.nimbo.backup.BackupPrefs.GAVE_UP, 0).apply()
                    gaveUp = 0
                    com.nimbo.backup.Backup.runNow(ctx)
                }
            )
        }
    }
    ask?.let { (uri, n) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.backup_existing_title)) },
            text = { Text(res.getQuantityString(R.plurals.backup_existing_body, n, n)) },
            confirmButton = {
                TextButton(onClick = { ask = null; addTree(uri) }) { Text(stringResource(R.string.backup_upload_all)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ask = null
                    // Solo lo nuevo: lo existente queda marcado sin subir.
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val log = com.nimbo.backup.BackupLog(ctx)
                        com.nimbo.backup.Backup.scan(ctx, uri).forEach { log.mark(it, 0L) }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { addTree(uri) }
                    }
                }) { Text(stringResource(R.string.backup_only_new)) }
            }
        )
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
