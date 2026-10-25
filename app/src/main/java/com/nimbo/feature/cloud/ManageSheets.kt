package com.nimbo.feature.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimbo.R
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.showTitle

enum class SheetMode { MENU, RENAME, DELETE }

// Gestión 7e: long-press → rename/move/download/delete. Un solo uso.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSheet(
    f: TgFile,
    onDismiss: () -> Unit,
    onRename: (String, (Boolean) -> Unit) -> Unit,
    onSelect: (TgFile) -> Unit,
    onDelete: (TgFile) -> Unit,
    onDownload: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(SheetMode.MENU) }
    var name by remember(f.id) { mutableStateOf(f.showTitle()) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(f.showTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when (mode) {
                SheetMode.RENAME -> {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Text(stringResource(R.string.rename_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { mode = SheetMode.MENU }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            busy = true
                            onRename(name.trim()) { ok ->
                                busy = false
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy && name.isNotBlank()) { Text(stringResource(R.string.save)) }
                    }
                }
                else -> {
                    SheetRow(stringResource(R.string.rename)) { mode = SheetMode.RENAME }
                    SheetRow(stringResource(R.string.select)) { onSelect(f) }
                    SheetRow(stringResource(R.string.download)) { onDownload(); onDismiss() }
                    SheetRow(stringResource(R.string.delete), red = true) { onDelete(f); onDismiss() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSheet(
    dir: String,
    count: Int,
    folders: List<String>,
    onDismiss: () -> Unit,
    onRename: (String, (Boolean) -> Unit) -> Unit,
    onDelete: ((Boolean) -> Unit) -> Unit,
    onDownload: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(SheetMode.MENU) }
    var path by remember(dir) { mutableStateOf(dir) }
    var busy by remember { mutableStateOf(false) }
    val title = if (dir.isEmpty()) stringResource(R.string.root) else dir.substringAfterLast('/')
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.items_count, count, count) + " · " + stringResource(R.string.retag_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when (mode) {
                SheetMode.RENAME -> {
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(stringResource(R.string.folder_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    folders.filter { it != dir }.take(6).forEach { fo ->
                        TextButton(onClick = { path = fo }, enabled = !busy) { Text(fo) }
                    }
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { mode = SheetMode.MENU }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            busy = true
                            onRename(path) { ok ->
                                busy = false
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy && path.isNotBlank()) { Text(stringResource(R.string.save)) }
                    }
                }
                SheetMode.DELETE -> {
                    Text(pluralStringResource(R.plurals.delete_folder_confirm, count, count), color = cs.error)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { mode = SheetMode.MENU }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            busy = true
                            onDelete { ok ->
                                busy = false
                                if (ok) onDismiss()
                            }
                        }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                    }
                }
                else -> {
                    SheetRow(stringResource(R.string.rename_move)) { mode = SheetMode.RENAME }
                    SheetRow(stringResource(R.string.download_folder)) { onDownload(); onDismiss() }
                    SheetRow(stringResource(R.string.delete_folder), red = true) { mode = SheetMode.DELETE }
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

