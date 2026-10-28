package com.nimbo.feature.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimbo.R
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.countRecursive
import com.nimbo.data.cloud.incomplete
import com.nimbo.data.cloud.showTitle
import com.nimbo.player.progressFrac
import com.nimbo.ui.common.EmptyState
import com.nimbo.ui.common.EqBars
import com.nimbo.ui.common.LeadingCircle
import com.nimbo.ui.common.fileArt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesView(
    listing: Listing,
    all: List<TgFile>,
    dir: String,
    query: String,
    grid: Boolean,
    onDir: (String) -> Unit,
    onPlay: (TgFile) -> Unit,
    onOpenInTg: (TgFile) -> Unit,
    onPreview: (TgFile) -> Unit,
    onManage: (TgFile) -> Unit,
    onManageDir: (String) -> Unit,
    playingId: String?,
    playing: Boolean,
    playP: Float,
    positions: Map<Long, Long>,
    syncing: Boolean,
    selection: Set<String>,
    onToggle: (TgFile) -> Unit,
    // Fotos: galería densa sin pie.
    compact: Boolean = false,
    // Hueco abajo para que los FABs (solo en Files) no tapen el último ítem.
    fabSpace: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val subs = listing.subs
    val files = listing.files
    val searching = listing.searching
    val showCrumb = listing.showCrumb
    val flat = listing.flat
    fun label(path: String) = if (searching) path else path.substringAfterLast('/')
    val incompleteLabel = stringResource(R.string.incomplete)
    val lockedLabel = stringResource(R.string.enc_locked)
    fun meta(f: TgFile) = listOfNotNull(
        lockedLabel.takeIf { f.locked },
        incompleteLabel.takeIf { f.incomplete() },
        f.folder.takeIf { searching && it.isNotEmpty() },
        f.meta
    ).joinToString(" · ")
    if (subs.isEmpty() && files.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            SubHeader(syncing, dir, onDir, showCrumb)
            if (searching) EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.no_results),
                body = stringResource(R.string.no_match, query)
            ) else if (flat) EmptyState(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.nothing_here),
                body = stringResource(R.string.upload_from_files)
            ) else EmptyState(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.cloud_empty),
                body = stringResource(R.string.cloud_empty_body)
            )
        }
    } else if (!grid) {
        val listState = rememberLazyListState()
        LaunchedEffect(dir, query) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (fabSpace) 88.dp else 16.dp)
        ) {
            stickyHeader { SubHeader(syncing, dir, onDir, showCrumb) }
            items(subs, key = { "d$it" }) { s ->
                FolderRow(
                    name = label(s),
                    count = countRecursive(all, s),
                    onOpen = { onDir(s) },
                    onManage = { onManageDir(s) }
                )
            }
                items(files, key = { it.id }) { f ->
                    val current = f.id == playingId
                    val sel = f.id in selection
                    val open = {
                        if (selection.isNotEmpty()) onToggle(f)
                        else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) onPreview(f)
                        else onPlay(f)
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { open() },
                                onLongClick = { if (selection.isNotEmpty()) onToggle(f) else onManage(f) }
                            )
                            .then(if (sel) Modifier.background(cs.primaryContainer.copy(alpha = 0.35f)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (f.kind == TgKind.AUDIO) {
                            // Cover circular + badge audio; si no, play redondo.
                            // Vale hash embebido o thumb linkeado (herencia de padres).
                            val cover = fileArt(f, mini = false)
                            if (cover != null) {
                                Image(
                                    cover, null,
                                    Modifier.size(40.dp).clip(CircleShape).clickable { onPlay(f) },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                PlayCircle(current = current, playing = playing) { onPlay(f) }
                            }
                        } else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) {
                            val bmp = fileArt(f)
                            if (bmp != null) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            } else {
                                LeadingCircle(
                                    if (f.kind == TgKind.IMAGE) Icons.Filled.Image
                                    else Icons.Filled.Movie
                                )
                            }
                        } else {
                            LeadingCircle(
                                when (f.kind) {
                                    TgKind.DOC -> Icons.Filled.Description
                                    else -> Icons.Filled.FolderZip
                                }
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.showTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(meta(f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Empezado (9a): la onda (audio) o una barra fina marcan por dónde va.
                            val saved = progressFrac(positions[f.messageId] ?: 0L, f.durSec)
                            if (f.kind == TgKind.AUDIO) {
                                WaveBars(
                                    seed = f.id,
                                    progress = if (current) playP else saved,
                                    modifier = Modifier.fillMaxWidth().height(26.dp)
                                )
                            } else if (saved > 0f) {
                                LinearProgressIndicator(
                                    progress = { saved },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)
                                )
                            }
                        }
                        if (current) EqBars(playing, cs.primary)
                        IconButton(onClick = { onOpenInTg(f) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_in_tg), tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            // Grid sin stickyHeader en foundation 1.7: header fijo arriba
            // (siempre visible = sticky efectivo) + grid scrolleable.
            Column(Modifier.fillMaxSize()) {
                SubHeader(syncing, dir, onDir, showCrumb)
                val gridState = rememberLazyGridState()
                LaunchedEffect(dir, query) { gridState.scrollToItem(0) }
                // Adaptive: 2 columnas en vertical, 4-5 en horizontal.
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (compact) 110.dp else 160.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, if (fabSpace) 88.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
            ) {
                items(subs, key = { "d$it" }) { s ->
                    val path = s
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.combinedClickable(
                            onClick = { onDir(path) },
                            onLongClick = { onManageDir(path) }
                        )
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            // Carpetas sin carátula: solo icono.
                            Icon(Icons.Filled.Folder, null, modifier = Modifier.size(48.dp), tint = cs.onSecondaryContainer)
                            // Badge carpeta: que se lea como carpeta, no archivo.
                            Box(
                                Modifier.align(Alignment.BottomStart).padding(8.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(cs.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Folder, stringResource(R.string.folder),
                                    tint = cs.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label(s), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    pluralStringResource(R.plurals.items_count, countRecursive(all, path), countRecursive(all, path)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSecondaryContainer,
                                    maxLines = 1
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = cs.onSecondaryContainer)
                        }
                    }
                }
                items(files, key = { it.id }) { f ->
                    val current = f.id == playingId
                    val sel = f.id in selection
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
                        shape = MaterialTheme.shapes.large,
                        border = if (sel) BorderStroke(2.dp, cs.primary) else null,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) onToggle(f)
                                else if (f.kind == TgKind.IMAGE || f.kind == TgKind.VIDEO) onPreview(f)
                                else onPlay(f)
                            },
                            onLongClick = { if (selection.isNotEmpty()) onToggle(f) else onManage(f) }
                        )
                    ) {
                        // Art: thumb real si hay (foto/video/caratula),
                        // si no waveform (audio) o icono.
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).background(cs.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = fileArt(f)
                            if (bmp != null) {
                                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else if (f.kind == TgKind.AUDIO) {
                                WaveBars(
                                    seed = f.id,
                                    progress = if (current) playP else progressFrac(positions[f.messageId] ?: 0L, f.durSec),
                                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)
                                )
                            } else {
                                Icon(
                                    when (f.kind) {
                                        TgKind.IMAGE -> Icons.Filled.Image
                                        TgKind.VIDEO -> Icons.Filled.Movie
                                        TgKind.DOC -> Icons.Filled.Description
                                        else -> Icons.Filled.FolderZip
                                    },
                                    null, modifier = Modifier.size(48.dp), tint = cs.onSurfaceVariant
                                )
                            }
                            val saved = progressFrac(positions[f.messageId] ?: 0L, f.durSec)
                            if (saved > 0f && !current && bmp != null) {
                                LinearProgressIndicator(
                                    progress = { saved },
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                                )
                            }
                            if (f.kind == TgKind.AUDIO) {
                                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                    PlayCircle(current = current, playing = playing, small = true) { onPlay(f) }
                                }
                            } else if (f.kind == TgKind.VIDEO) {
                                Box(Modifier.align(Alignment.Center)) {
                                    PlayCircle(current = false, playing = false, small = true) { onPreview(f) }
                                }
                            }
                        }
                        if (!compact) Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                // minLines = 2 fija altura: cards uniformes aunque título corte.
                                Text(f.showTitle(), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(meta(f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onOpenInTg(f) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_in_tg), tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Subheader sticky: breadcrumb + barra de sync, con fondo surface
// (los items pasan por debajo). Filtros y vista viven en la hoja ⚲.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubHeader(
    syncing: Boolean,
    dir: String,
    onDir: (String) -> Unit,
    showCrumb: Boolean
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        // Breadcrumb 7f: raíz + segmentos clicables.
        if (showCrumb) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onDir("") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Home, stringResource(R.string.root), tint = cs.primary)
                }
                var acc = ""
                dir.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    Text("/", color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
                    acc = if (acc.isEmpty()) seg else "$acc/$seg"
                    val target = acc
                    TextButton(onClick = { onDir(target) }) {
                        Text(seg, maxLines = 1)
                    }
                }
            }
        }
        if (syncing) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// Fila carpeta 7f: entra al dir. Un solo uso, queda aquí.
@Composable
private fun FolderRow(
    name: String,
    count: Int,
    onOpen: () -> Unit,
    onManage: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onManage)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeadingCircle(Icons.Filled.Folder)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.items_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

// Play redondo prototipo: filled cuando es el actual, tonal resto.
@Composable
private fun PlayCircle(current: Boolean, playing: Boolean, small: Boolean = false, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = if (small) 32.dp else 40.dp
    Box(
        Modifier.size(s).clip(CircleShape)
            .background(if (current) cs.primary else cs.secondaryContainer)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (current && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            null,
            tint = if (current) cs.onPrimary else cs.onSecondaryContainer,
            modifier = Modifier.size(if (small) 18.dp else 22.dp)
        )
    }
}

// Waveform determinista por archivo (estable entre recomposiciones).
// played tiñe barras hasta progress; resto tenue. Un solo uso.
@Composable
private fun WaveBars(seed: String, progress: Float, modifier: Modifier = Modifier, bars: Int = 32) {
    val heights = remember(seed) {
        val r = kotlin.random.Random(seed.hashCode())
        List(bars) { 0.2f + r.nextFloat() * 0.8f }
    }
    val cs = MaterialTheme.colorScheme
    // SpaceEvenly: barras repartidas en todo el ancho, no apiladas a la izq.
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier.width(2.dp).fillMaxHeight(fraction = h)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (i.toFloat() / bars <= progress && progress > 0f) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    }
}

