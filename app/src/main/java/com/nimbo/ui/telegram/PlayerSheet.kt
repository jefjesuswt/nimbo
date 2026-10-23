package com.nimbo.ui.telegram

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// Player full-screen del prototipo (sheet-player): arte, seek, controles,
// repeat/shuffle/speed/timer/download, cola. Solo audio+video.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheet(onClose: () -> Unit, onMsg: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val track by TgPlayer.track.collectAsState()
    val playing by TgPlayer.playing.collectAsState()
    val posMs by TgPlayer.posMs.collectAsState()
    val durMs by TgPlayer.durMs.collectAsState()
    val queue by TgPlayer.queue.collectAsState()
    val index by TgPlayer.index.collectAsState()
    val repeat by TgPlayer.repeat.collectAsState()
    val shuffle by TgPlayer.shuffle.collectAsState()
    val speed by TgPlayer.speed.collectAsState()
    val timer by TgPlayer.timer.collectAsState()
    val favs by TgPlayer.favs.collectAsState()
    val buffering by TgPlayer.buffering.collectAsState()
    val dl by TgPlayer.dlProgress.collectAsState()
    val f = track ?: return
    // Back cierra el player (no navega la carpeta detrás).
    androidx.activity.compose.BackHandler { onClose() }
    // El art del track suena aquí: pide su thumb (la grilla no lo vio).
    LaunchedEffect(f.id) {
        if (f.thumbFileId != 0) TgThumbs.fetch(f.thumbFileId)
        if (f.coverHash.isEmpty()) TgCovers.fetch(f, TgRepo::noteCover)
    }
    val isVideo = f.kind == TgKind.VIDEO

    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val shown = drag ?: frac
    val fav = f.id in favs
    val speedLb = if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

    Column(
        Modifier.fillMaxSize().background(cs.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        // Top: minimizar + contexto + menú.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Minimize") }
            Text(
                "${if (isVideo) "Video" else "Audio"} · ${index + 1} of ${queue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menu = false; details = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = {
                            menu = false
                            TgRepo.download(ctx, f) { ok ->
                                onMsg(if (ok) "Saved to Download/Nimbo" else "Download failed")
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirmDelete = true }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Arte: video 16:9, si no cover o glifo rotando con tono por índice.
        if (isVideo) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.extraLarge).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { c ->
                        androidx.media3.ui.PlayerView(c).apply {
                            useController = false
                            player = TgPlayer.exo
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                if (buffering) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { androidx.compose.material3.CircularProgressIndicator() }
                }
            }
        } else {
            val thumbs by TgThumbs.maps.collectAsState()
            val covers by TgCovers.covers.collectAsState()
            val bmp = thumbs[f.thumbFileId]
                ?: f.coverHash.takeIf { it.isNotEmpty() }?.let { covers[it] }
            val tones = listOf(
                cs.primaryContainer to cs.onPrimaryContainer,
                cs.secondaryContainer to cs.onSecondaryContainer,
                cs.tertiaryContainer to cs.onTertiaryContainer
            )
            val (bg, fg) = tones[(index % 3 + 3) % 3]
            val glyph: ImageVector = when (artGlyphFor(f.name)) {
                "podcasts" -> Icons.Filled.Podcasts
                "mic" -> Icons.Filled.Mic
                "school" -> Icons.Filled.School
                else -> Icons.Filled.MusicNote
            }
            val inf = rememberInfiniteTransition(label = "art")
            val rot by inf.animateFloat(
                0f, 360f,
                infiniteRepeatable(tween(30000, easing = LinearEasing)),
                label = "rot"
            )
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(MaterialTheme.shapes.extraLarge).background(bg),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        glyph, null,
                        tint = fg,
                        modifier = Modifier.size(96.dp)
                            .graphicsLayer { rotationZ = if (playing) rot else 0f }
                    )
                }
                if (buffering) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { androidx.compose.material3.CircularProgressIndicator(color = Color.White) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Meta + fav.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.showTitle(), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    run {
                        val d = dl
                        if (buffering && d != null) "Downloading… ${(d * 100).toInt()}%"
                        else "${f.name.substringAfterLast('.', "").uppercase()} · ${formatBytes(f.sizeBytes)} · Saved Messages"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = {
                TgPlayer.toggleFav()
                onMsg(if (f.id in TgPlayer.favs.value) "Added to favorites" else "Removed from favorites")
            }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (fav) "Remove from favorites" else "Add to favorites",
                    tint = if (fav) cs.primary else cs.onSurface
                )
            }
        }

        // Seek.
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { TgPlayer.seekTo((it * durMs).toLong()) }
                drag = null
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtDur(((drag ?: frac) * durMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(fmtDur((durMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }

        // Controles.
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(64.dp).clip(CircleShape).background(cs.secondaryContainer)
                    .clickableNoRipple { TgPlayer.prev() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp)) }
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).height(96.dp)
                    .clip(if (playing) MaterialTheme.shapes.large else CircleShape)
                    .background(if (playing) cs.primaryContainer else cs.primary)
                    .clickableNoRipple { TgPlayer.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pause" else "Play",
                    tint = if (playing) cs.onPrimaryContainer else cs.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
            androidx.compose.foundation.layout.Box(
                Modifier.size(64.dp).clip(CircleShape).background(cs.secondaryContainer)
                    .clickableNoRipple { TgPlayer.next() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.SkipNext, "Next", tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp)) }
        }

        // Acciones.
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ActButton(
                active = repeat != "off",
                onClick = { TgPlayer.cycleRepeat() },
                label = "Repeat: $repeat",
                icon = if (repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat
            )
            ActButton(
                active = shuffle,
                onClick = { TgPlayer.toggleShuffle() },
                label = "Shuffle",
                icon = Icons.Filled.Shuffle
            )
            androidx.compose.material3.TextButton(onClick = { TgPlayer.cycleSpeed() }) {
                Text(speedLb, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = {
                TgRepo.download(ctx, f) { ok ->
                    onMsg(if (ok) "Saved to Download/Nimbo" else "Download failed")
                }
            }) { Icon(Icons.Filled.Download, "Download") }
            Box {
                IconButton(onClick = { TgPlayer.cycleTimer() }) {
                    Icon(Icons.Filled.Timer, if (timer > 0) "Sleep timer: $timer min" else "Sleep timer off", tint = if (timer > 0) cs.primary else cs.onSurface)
                }
                if (timer > 0) {
                    Text(
                        "$timer",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd)
                            .background(cs.primary, CircleShape).padding(horizontal = 4.dp)
                    )
                }
            }
        }

        // Cola.
        Text(
            "Up next · ${queue.size}",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 32.dp, bottom = 4.dp)
        )
        queue.forEachIndexed { i, q ->
            val cur = i == index
            com.nimbo.ui.common.NimboListRow(
                title = q.showTitle(),
                subtitle = q.meta,
                leading = {
                    com.nimbo.ui.common.LeadingCircle(
                        if (q.kind == TgKind.VIDEO) Icons.Filled.Movie else Icons.Filled.MusicNote
                    )
                },
                onOpen = { TgPlayer.playAt(i) },
                badge = {
                    if (cur) Icon(Icons.Filled.GraphicEq, "Playing", tint = cs.primary)
                }
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            icon = { Icon(Icons.Filled.Info, null) },
            title = { Text(f.name) },
            text = {
                Text(
                    "${if (isVideo) "Video" else "Audio"}" +
                        (if (f.dur.isNotEmpty()) " · ${f.dur}" else "") +
                        " · ${formatBytes(f.sizeBytes)} · Saved in Telegram Saved Messages. " +
                        "Streams on play; nothing is stored on this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = { details = false }) { Text("Close") }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete from Telegram?") },
            text = { Text("${f.showTitle()} will be removed from your Saved Messages.") },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    TgRepo.delete(f) { ok ->
                        if (ok) {
                            TgPlayer.stop()
                            onClose()
                        }
                        onMsg(if (ok) "Deleted" else "Delete failed")
                    }
                }) { Text("Delete") }
            }
        )
    }
}

@Composable
private fun ActButton(active: Boolean, onClick: () -> Unit, label: String, icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(if (active) cs.secondaryContainer else Color.Transparent)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, label,
            tint = if (active) cs.onSecondaryContainer else cs.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}

// Clickable sin ripple (el contenedor ya da feedback de forma).
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.then(
        clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick
        )
    )
}
