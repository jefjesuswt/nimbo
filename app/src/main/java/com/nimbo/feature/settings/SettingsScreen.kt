package com.nimbo.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.core.prefs.ThemeMode
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.crypto.EncState
import com.nimbo.data.translate.Translator
import com.nimbo.ui.common.NimboScaffold
import com.nimbo.ui.common.RadioDialog
import com.nimbo.ui.common.SectionLabel
import com.nimbo.ui.theme.Seed

// s-settings de index.html, filas estilo Mihon (icono plano, sin círculo).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val ap by vm.appearance.collectAsStateWithLifecycle()
    val dynamic = ap.dynamic
    var themeDlg by remember { mutableStateOf(false) }
    var aboutDlg by remember { mutableStateOf(false) }
    var langDlg by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    NimboScaffold(
        title = stringResource(R.string.tab_more)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Group(stringResource(R.string.settings_appearance)) {
                    PrefRow(
                        icon = Icons.Filled.Palette, title = stringResource(R.string.settings_theme),
                        sub = stringResource(themeModeLabel(ap.theme)),
                        onClick = { themeDlg = true }
                    )
                    PrefRow(
                        icon = Icons.Filled.Palette, title = stringResource(R.string.settings_dynamic),
                        sub = stringResource(R.string.settings_dynamic_sub),
                        trailing = { Switch(dynamic, vm::setDynamic) }
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
                                listOf(Seed.OCEAN to stringResource(R.string.seed_ocean), Seed.MEADOW to stringResource(R.string.seed_meadow), Seed.DUSK to stringResource(R.string.seed_dusk)).forEach { (v, l) ->
                                    val sel = ap.seed == v
                                    Column(
                                        Modifier.weight(1f).clip(MaterialTheme.shapes.large)
                                            .clickable { vm.setSeed(v) }
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier.size(56.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(v.hex))),
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
                TelegramGroup(vm)
                EncryptionGroup(vm)
                TranslationGroup(vm)
                Group(stringResource(R.string.settings_notifications)) {
                    PrefRow(
                        icon = Icons.Filled.Tune, title = stringResource(R.string.settings_notif_categories),
                        sub = stringResource(R.string.settings_notif_sub),
                        trailIcon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            })
                        }
                    )
                }
                Group(stringResource(R.string.settings_about)) {
                    PrefRow(icon = Icons.Filled.Info, title = stringResource(R.string.settings_about_nimbo), sub = stringResource(R.string.version, remember { appVersion(ctx) })) {
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
            options = ThemeMode.entries.map { it.key to stringResource(themeModeLabel(it)) },
            initial = ap.theme.key,
            onDismiss = { themeDlg = false },
            onConfirm = {
                themeDlg = false
                vm.setTheme(ThemeMode.of(it))
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
    if (aboutDlg) AboutDialog(onDismiss = { aboutDlg = false })
}

// Traducción del lector: proveedor (solo si hay más de uno, build
// personal) + permitir bajar modelos de ML Kit con datos móviles.
@Composable
private fun TranslationGroup(vm: SettingsViewModel) {
    val all = vm.translatorsAll
    val cur = vm.translator
    var dlg by remember { mutableStateOf(false) }
    fun note(t: Translator) = if (t.onDevice) R.string.tr_on_device else R.string.tr_sends_google
    Group(stringResource(R.string.tr_group)) {
        if (all.size > 1) PrefRow(
            icon = Icons.Filled.Translate,
            title = stringResource(R.string.tr_provider),
            sub = stringResource(cur.label) + " · " + stringResource(note(cur)),
            onClick = { dlg = true }
        )
        PrefRow(
            icon = Icons.Filled.Translate,
            title = stringResource(R.string.tr_models_mobile),
            sub = stringResource(R.string.tr_models_mobile_sub),
            trailing = { Switch(vm.modelsOnMobile, vm::allowModelsOnMobile) }
        )
    }
    if (dlg) RadioDialog(
        title = stringResource(R.string.tr_provider),
        options = all.map { it.id to stringResource(it.label) + " · " + stringResource(note(it)) },
        initial = cur.id,
        onDismiss = { dlg = false },
        onConfirm = { id ->
            dlg = false
            vm.setTranslator(id)
        }
    )
}

// Cifrado (spec 12). Sin configurar → crear contraseña; en otro teléfono
// (o tras bloquear) → desbloquear; listo → cifrar subidas o bloquear.
@Composable
private fun EncryptionGroup(vm: SettingsViewModel) {
    val state by vm.encState.collectAsStateWithLifecycle()
    Group(stringResource(R.string.enc_group)) {
        when (state) {
            EncState.OFF -> PrefRow(
                icon = Icons.Filled.Lock, title = stringResource(R.string.enc_setup),
                sub = stringResource(R.string.enc_setup_sub),
                onClick = { vm.openEncDialog(EncDialog.SETUP) }
            )
            EncState.LOCKED -> PrefRow(
                icon = Icons.Filled.Lock, title = stringResource(R.string.enc_unlock),
                sub = stringResource(R.string.enc_unlock_sub),
                onClick = { vm.openEncDialog(EncDialog.UNLOCK) }
            )
            EncState.READY -> {
                PrefRow(
                    icon = Icons.Filled.Lock, title = stringResource(R.string.enc_uploads),
                    sub = stringResource(R.string.enc_uploads_sub),
                    trailing = { Switch(vm.encryptUploads, vm::encryptUploads) }
                )
                EncryptExistingRow(vm)
                PrefRow(
                    icon = Icons.Filled.LockOpen, title = stringResource(R.string.enc_lock),
                    sub = stringResource(R.string.enc_lock_sub),
                    onClick = vm::lockEnc
                )
            }
        }
    }
    vm.encDialog?.let { PasswordDialog(vm, it) }
}

// Solo si queda algo en claro (o ya está en marcha).
@Composable
private fun EncryptExistingRow(vm: SettingsViewModel) {
    val count by vm.plainCount.collectAsStateWithLifecycle()
    val running by vm.encrypting.collectAsStateWithLifecycle()
    if (count == 0 && !running) return
    PrefRow(
        icon = Icons.Filled.Lock, title = stringResource(R.string.enc_existing),
        sub = if (running) stringResource(R.string.enc_existing_running) else stringResource(R.string.enc_existing_sub, count),
        onClick = { if (!running) vm.encConfirm = true }
    )
    if (vm.encConfirm) AlertDialog(
        onDismissRequest = { vm.encConfirm = false },
        title = { Text(stringResource(R.string.enc_existing)) },
        text = { Text(stringResource(R.string.enc_existing_confirm)) },
        confirmButton = { TextButton(onClick = vm::encryptExisting) { Text(stringResource(R.string.enc_existing_action)) } },
        dismissButton = { TextButton(onClick = { vm.encConfirm = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PasswordDialog(vm: SettingsViewModel, d: EncDialog) {
    val cs = MaterialTheme.colorScheme
    var pw by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val setup = d == EncDialog.SETUP
    AlertDialog(
        onDismissRequest = { if (!vm.encBusy) vm.openEncDialog(null) },
        title = { Text(stringResource(if (setup) R.string.enc_setup else R.string.enc_unlock)) },
        text = {
            Column {
                if (setup) Text(
                    stringResource(R.string.enc_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                PasswordField(pw, { pw = it }, stringResource(R.string.enc_password), vm.encError != null)
                if (setup) {
                    Spacer(Modifier.height(8.dp))
                    PasswordField(repeat, { repeat = it }, stringResource(R.string.enc_password_repeat), vm.encError != null)
                }
                vm.encError?.let {
                    Text(stringResource(it), color = cs.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (vm.encBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !vm.encBusy && pw.isNotEmpty(), onClick = { vm.submitPassword(pw, repeat) }) {
                Text(stringResource(if (setup) R.string.enc_setup_action else R.string.enc_unlock_action))
            }
        },
        dismissButton = {
            TextButton(enabled = !vm.encBusy, onClick = { vm.openEncDialog(null) }) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
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

// Keys TDLib + estado sesión. Keys solo cifradas en celu, nunca en repo.
@Composable
private fun TelegramGroup(vm: SettingsViewModel) {
    val cs = MaterialTheme.colorScheme
    val auth by vm.authState.collectAsStateWithLifecycle()
    val saved by vm.keys.collectAsStateWithLifecycle()
    var apiId by remember(saved) { mutableStateOf(saved?.id?.toString() ?: "") }
    var apiHash by remember(saved) { mutableStateOf(saved?.hash ?: "") }
    var err by remember { mutableStateOf(false) }
    // Lo guardado: Save solo si se tocó algo; Forget solo si hay keys.
    var stored by remember(saved) { mutableStateOf(apiId to apiHash) }
    val dirty = (apiId to apiHash.trim()) != stored
    Group("Telegram") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                when (auth) {
                    TgAuthState.READY -> stringResource(R.string.tg_connected)
                    TgAuthState.WAIT_CODE,
                    TgAuthState.WAIT_PASSWORD -> stringResource(R.string.tg_login_progress)
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
                keyboardOptions = KeyboardOptions(
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
                keyboardOptions = KeyboardOptions(
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
            if (dirty || stored.first.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (stored.first.isNotEmpty()) TextButton(onClick = {
                    vm.forget()
                    apiId = ""
                    apiHash = ""
                    stored = "" to ""
                }) { Text(stringResource(R.string.forget_logout)) }
                if (dirty) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        err = !vm.saveKeys(apiId, apiHash)
                        if (!err) stored = apiId to apiHash.trim()
                    }) { Text(stringResource(R.string.save)) }
                }
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
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        trailing?.invoke()
        trailIcon?.let { Icon(it, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}
