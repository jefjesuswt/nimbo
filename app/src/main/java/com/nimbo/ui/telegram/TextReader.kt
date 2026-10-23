package com.nimbo.ui.telegram

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.nimbo.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Lector multi-formato (txt/md/srt/csv/código…) con encoding detectado
// (UTF-8/16, Shift_JIS, Latin-1) + traducción con par de idiomas por el
// proveedor elegido en More (Translators: ML Kit en el teléfono por defecto).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextReader(f: TgFile, onClose: () -> Unit, onMsg: (String) -> Unit) {
    val ctx = LocalContext.current
    val rs = LocalResources.current
    val cs = MaterialTheme.colorScheme
    var text by remember(f.messageId) { mutableStateOf<String?>(null) }
    var enc by remember(f.messageId) { mutableStateOf("") }
    var tooBig by remember(f.messageId) { mutableStateOf(false) }
    var tab by remember(f.messageId) { mutableStateOf(0) } // 0 original, 1 traducido
    var translated by remember(f.messageId) { mutableStateOf<String?>(null) }
    var working by remember(f.messageId) { mutableStateOf(false) }
    // Estado visible mientras traduce ("descargando modelo…").
    var status by remember(f.messageId) { mutableStateOf<Int?>(null) }
    val tr = remember { Translators.current(ctx) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var langs by remember(f.messageId) { mutableStateOf(false) }
    var src by remember(f.messageId) { mutableStateOf("auto") }
    var tgt by remember(f.messageId) { mutableStateOf(systemLang(tr.langs)) }
    // Hermano guardado ("05_x.es.txt"): se abre sin re-traducir.
    val cloud by TgRepo.files.collectAsState()
    val sib = cloud.firstOrNull { it.folder == f.folder && it.name == siblingName(f.name, tgt) }
    LaunchedEffect(sib?.messageId) {
        val s = sib
        if (s != null && translated == null) {
            val got = withContext(Dispatchers.IO) {
                val path = TgDownload.awaitFile(s.fileId, 60_000) ?: return@withContext null
                runCatching {
                    java.io.File(path).inputStream().use { it.readBytes() }.take(512 * 1024).toByteArray()
                        .let { decodeText(it).first }
                }.getOrNull()
            }
            if (got != null) {
                translated = got
                tab = 1
            }
        }
    }
    val auto = stringResource(R.string.lang_auto)
    val pairLabel = "${langName(src, auto)} → ${langName(tgt, auto)}"

    LaunchedEffect(f.messageId) {
        if (f.sizeBytes > 2L * 1024 * 1024) {
            tooBig = true
            return@LaunchedEffect
        }
        val res = withContext(Dispatchers.IO) {
            val path = TgDownload.awaitFile(f.fileId, 60_000) ?: return@withContext null
            runCatching {
                val bytes = java.io.File(path).inputStream().use { it.readBytes() }.take(512 * 1024).toByteArray()
                decodeText(bytes)
            }.getOrNull()
        }
        if (res == null && !tooBig) {
            onMsg(rs.getString(R.string.could_not_open_text))
        } else if (res != null) {
            text = res.first
            enc = res.second
        }
    }

    fun doTranslate(s: String, t: String) {
        val orig = text ?: return
        if (orig.isBlank()) {
            onMsg(rs.getString(R.string.nothing_to_translate))
            return
        }
        working = true
        status = null
        translated = null
        tab = 1
        scope.launch {
            val out = runCatching { Translators.translate(ctx, tr, orig, s, t) { status = it } }
            working = false
            out.onSuccess { translated = it }.onFailure {
                onMsg(rs.getString((it as? TrError)?.res ?: R.string.translation_failed))
                tab = 0
            }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.close)) }
            Column(Modifier.weight(1f)) {
                Text(f.showTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                    formatBytes(f.sizeBytes) + if (enc.isNotEmpty()) " · $enc" else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
            IconButton(onClick = { langs = true }) { Icon(Icons.Filled.Translate, pairLabel) }
            IconButton(onClick = {
                TgRepo.download(ctx, f) { ok ->
                    onMsg(if (ok) rs.getString(R.string.saved_to_downloads) else rs.getString(R.string.download_failed))
                }
            }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
        }
        when {
            tooBig -> Text(
                stringResource(R.string.too_large),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            working -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    status?.let { stringResource(it) } ?: stringResource(R.string.translating, pairLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            tab == 1 && translated != null -> Text(
                translated!!,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            )
            text != null -> Text(
                text!!,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            )
            else -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
    if (langs) {
        TranslateModal(
            src = src, tgt = tgt, tab = tab, tr = tr,
            hasTranslation = translated != null,
            savedName = sib?.name,
            onDismiss = { langs = false },
            onTranslate = { s, t ->
                src = s
                tgt = t
                langs = false
                doTranslate(s, t)
            },
            onView = {
                if (it == 1 && translated == null) {
                    langs = false
                    doTranslate(src, tgt)
                } else {
                    tab = it
                    langs = false
                }
            },
            onSave = {
                val cur = translated
                if (cur != null) {
                    TgRepo.saveTranslation(f, tgt, cur) { ok ->
                        onMsg(if (ok) rs.getString(R.string.saved_name, siblingName(f.name, tgt)) else rs.getString(R.string.save_failed))
                    }
                }
                langs = false
            }
        )
    }
}

private fun systemLang(langs: List<String>): String {
    val tag = Locale.getDefault().language
    return if (tag in langs) tag else "es"
}

// Nombre del idioma en el idioma de la app; "auto" lo pone la UI.
private fun langName(tag: String, auto: String): String =
    if (tag == "auto") auto
    else Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.uppercase() }.ifEmpty { tag }

// Modal del botón traducir: pestañas Languages / View / Save.
// Todo el flujo de traducción vive aquí, no en el cuerpo del lector.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateModal(
    src: String, tgt: String, tab: Int, tr: Translator,
    hasTranslation: Boolean, savedName: String?,
    onDismiss: () -> Unit,
    onTranslate: (String, String) -> Unit,
    onView: (Int) -> Unit,
    onSave: () -> Unit
) {
    var s by remember { mutableStateOf(src) }
    var t by remember { mutableStateOf(tgt) }
    var page by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(selectedTabIndex = page) {
                    listOf(stringResource(R.string.tr_languages), stringResource(R.string.tr_view), stringResource(R.string.save)).forEachIndexed { i, l ->
                        Tab(selected = page == i, onClick = { page = i }, text = { Text(l) })
                    }
                }
                when (page) {
                    0 -> {
                        Text(stringResource(R.string.tr_from), style = MaterialTheme.typography.labelMedium)
                        LangSelect(listOf("auto") + tr.langs, s) { s = it }
                        Text(stringResource(R.string.tr_to), style = MaterialTheme.typography.labelMedium)
                        LangSelect(tr.langs, t) { t = it }
                        // Privacidad: dónde ocurre la traducción.
                        Text(
                            stringResource(tr.label) + " · " +
                                stringResource(if (tr.onDevice) R.string.tr_on_device else R.string.tr_sends_google),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    1 -> {
                        listOf(stringResource(R.string.tr_original), stringResource(R.string.tr_translated)).forEachIndexed { i, l ->
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .clickable { onView(i) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = tab == i, onClick = { onView(i) })
                                Text(
                                    if (i == 1 && !hasTranslation) stringResource(R.string.tr_translate_now, l) else l,
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                    2 -> {
                        Text(
                            if (savedName != null) stringResource(R.string.tr_saved_as, savedName) else stringResource(R.string.tr_not_saved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.tr_sibling_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (page) {
                0 -> TextButton(onClick = { onTranslate(s, t) }) { Text(stringResource(R.string.translate)) }
                2 -> TextButton(onClick = onSave, enabled = hasTranslation) {
                    Text(if (savedName != null) stringResource(R.string.update) else stringResource(R.string.save))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// Select simple (TextField + menú estable, sin APIs experimentales).
@Composable
private fun LangSelect(tags: List<String>, sel: String, onTap: (String) -> Unit) {
    val auto = stringResource(R.string.lang_auto)
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = langName(sel, auto),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.open))
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(langName(tag, auto)) },
                    onClick = { onTap(tag); open = false }
                )
            }
        }
    }
}
