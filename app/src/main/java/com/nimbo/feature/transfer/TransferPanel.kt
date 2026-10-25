package com.nimbo.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.core.util.formatBytes
import com.nimbo.data.transfer.BatchLabel
import com.nimbo.data.transfer.BatchUi
import com.nimbo.data.transfer.ItemState
import com.nimbo.data.transfer.ResolveMode
import com.nimbo.data.transfer.TransferNotice
import com.nimbo.ui.common.RadioDialog
import kotlinx.coroutines.delay

@Composable
fun batchLabel(l: BatchLabel): String = when (l) {
    is BatchLabel.Name -> l.text
    is BatchLabel.Files -> pluralStringResource(R.plurals.files_count, l.count, l.count)
    BatchLabel.Root -> stringResource(R.string.root)
}

// Lotes arriba de la lista: el que corre con %, los en cola debajo (alto
// fijo + scroll, no empujan la lista). Avisos y conflictos, una vez.
@Composable
fun TransferPanel(snack: SnackbarHostState, vm: TransferViewModel = hiltViewModel()) {
    val res = LocalResources.current
    val batches by vm.batches.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    batches.firstOrNull { it.running }?.let {
        if (it.scan) ScanningCard { detailId = it.id } else UploadCard(it, { detailId = it.id }, { vm.cancel(it.id) })
    }
    batches.firstOrNull { it.id == detailId }?.let { b -> BatchDetail(b, { detailId = null }, { vm.cancel(b.id) }) }

    // Fallidos del lote: aviso con nombres.
    val failed by vm.failed.collectAsStateWithLifecycle()
    if (failed.isNotEmpty()) LaunchedEffect(failed) {
        snack.showSnackbar(res.getString(R.string.failed_list, failed.size, failed.take(3).joinToString() + if (failed.size > 3) "…" else ""))
        vm.clearFailed()
    }
    val notice by vm.notice.collectAsStateWithLifecycle()
    notice?.let { n ->
        LaunchedEffect(n) {
            snack.showSnackbar(
                res.getString(if (n == TransferNotice.UPLOAD_CANCELLED) R.string.upload_cancelled else R.string.download_cancelled)
            )
            vm.clearNotice()
        }
    }
    // Conflictos: existe → Replace / Keep both / Skip. Fuera = Cancel.
    val pending by vm.pending.collectAsStateWithLifecycle()
    pending?.let { p ->
        RadioDialog(
            title = pluralStringResource(R.plurals.already_exist, p.conflicts.size, p.conflicts.size, p.conflicts.take(3).joinToString { it.name } + if (p.conflicts.size > 3) "…" else ""),
            options = listOf(
                ResolveMode.REPLACE.name to stringResource(R.string.replace),
                ResolveMode.DUPLICATE.name to stringResource(R.string.keep_both),
                ResolveMode.SKIP.name to stringResource(R.string.skip)
            ),
            initial = ResolveMode.REPLACE.name,
            onDismiss = { vm.resolve(null) },
            onConfirm = { vm.resolve(ResolveMode.valueOf(it)) }
        )
    }
    val queued = batches.filter { !it.running }
    if (queued.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            queued.forEach { QueuedCard(it) { detailId = it.id } }
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
            Text(stringResource(R.string.scanning_folder), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// Card upload por lote: título del lote + archivo actual + % global.
// X = cancelar el lote (borra lo ya subido).
@Composable
private fun UploadCard(b: BatchUi, onOpen: () -> Unit, onCancel: () -> Unit) {
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
                    stringResource(if (b.down) R.string.downloading_name else R.string.uploading_name, batchLabel(b.label)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cancel))
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
private fun QueuedCard(b: BatchUi, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(batchLabel(b.label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.queued) + " · " + pluralStringResource(R.plurals.files_count, b.totalFiles, b.totalFiles),
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
private fun BatchDetail(b: BatchUi, onClose: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember(b.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(b.id) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val p = (b.done.toFloat() / b.total.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val elapsed = ((now - b.startMs) / 1000.0).coerceAtLeast(0.0)
    val speed = if (elapsed > 2 && b.done > 0) b.done / elapsed else 0.0
    val eta = if (speed > 0 && b.done < b.total) ((b.total - b.done) / speed).toLong() else -1L
    val doneN = b.items.count { it.state == ItemState.DONE }
    val failN = b.items.count { it.state == ItemState.FAILED }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (b.down) R.string.downloading_name else R.string.uploading_name, batchLabel(b.label)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.done_of, doneN, b.totalFiles) + if (failN > 0) " · " + pluralStringResource(R.plurals.n_failed, failN, failN) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    if (b.running || b.scan) {
                        TextButton(onClick = { onCancel(); onClose() }) { Text(stringResource(R.string.cancel)) }
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
                                if (b.scan) stringResource(R.string.scanning)
                                else if (speed > 0) "${formatBytes(speed.toLong())}/s" + if (eta >= 0) " · " + stringResource(R.string.eta_left, if (eta < 60) "${eta}s" else "${eta / 60}m${eta % 60}s") else ""
                                else if (b.running) stringResource(R.string.starting)
                                else stringResource(R.string.queued),
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
                LazyColumn(
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
                                ItemState.DONE -> Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                                ItemState.FAILED -> Icon(Icons.Filled.Error, null, tint = cs.error, modifier = Modifier.size(20.dp))
                                ItemState.RUNNING -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                else -> Icon(Icons.Filled.Schedule, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (it.state == ItemState.RUNNING && b.currentFrac > 0) "${(b.currentFrac * 100).toInt()}% · ${formatBytes(it.size)}"
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

