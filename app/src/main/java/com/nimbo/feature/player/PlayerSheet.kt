package com.nimbo.feature.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.core.util.fmtDur
import com.nimbo.core.util.formatBytes
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.showTitle
import com.nimbo.player.RepeatMode
import com.nimbo.player.artGlyphFor
import com.nimbo.ui.common.LeadingCircle
import com.nimbo.ui.common.NimboListRow
import com.nimbo.ui.common.TopSnackBox
import com.nimbo.ui.common.fileArt

// Player full-screen del prototipo (sheet-player): arte, seek, controles,
// repeat/shuffle/speed/timer/download, cola. Solo audio+video.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheet(vm: PlayerViewModel, onClose: () -> Unit) {
    val p = vm.player
    val cs = MaterialTheme.colorScheme
    val track by p.track.collectAsStateWithLifecycle()
    val playing by p.playing.collectAsStateWithLifecycle()
    val posMs by p.posMs.collectAsStateWithLifecycle()
    val durMs by p.durMs.collectAsStateWithLifecycle()
    val queue by p.queue.collectAsStateWithLifecycle()
    val index by p.index.collectAsStateWithLifecycle()
    val repeat by p.repeat.collectAsStateWithLifecycle()
    val shuffle by p.shuffle.collectAsStateWithLifecycle()
    val speed by p.speed.collectAsStateWithLifecycle()
    val timer by p.timer.collectAsStateWithLifecycle()
    val favs by p.favs.collectAsStateWithLifecycle()
    val buffering by p.buffering.collectAsStateWithLifecycle()
    val dl by p.dlProgress.collectAsStateWithLifecycle()
    val f = track ?: return
    // Avisos del player (descarga, favorito, borrar): snackbar propio, la
    // hoja tapa el de la pestaña.
    val snack = remember { SnackbarHostState() }
    val res = LocalResources.current
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it.resolve(res)) } }
    // Back cierra el player (no navega la carpeta detrás).
    BackHandler { onClose() }
    val isVideo = f.kind == TgKind.VIDEO

    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val shown = drag ?: frac
    val fav = f.id in favs
    val speedLb = if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

    // Horizontal: arte a la izquierda, controles + cola a la derecha.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topBar: @Composable () -> Unit = {
        // Top: minimizar + contexto + menú.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.minimize)) }
            Text(
                (if (isVideo) stringResource(R.string.kind_video) else stringResource(R.string.tab_audio)) + " · " + stringResource(R.string.position, index + 1, queue.size),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details)) },
                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menu = false; details = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download)) },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = {
                            menu = false
                            vm.download(f)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirmDelete = true }
                    )
                }
            }
        }
    }
    val artBox: @Composable (Modifier) -> Unit = { m ->
        // Arte: video 16:9, si no cover o glifo rotando con tono por índice.
        if (isVideo) {
            Box(
                m.aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.extraLarge).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { c ->
                        androidx.media3.ui.PlayerView(c).apply {
                            useController = false
                            player = p.exo
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                if (buffering) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            }
        } else {
            // El art del track suena aquí: lo pide (la grilla no lo vio).
            val bmp = fileArt(f, mini = false)
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
                m.aspectRatio(1f)
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
                    ) { CircularProgressIndicator(color = Color.White) }
                }
            }
        }
    }
    val panel: @Composable () -> Unit = {
        // Meta + fav.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.showTitle(), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    run {
                        val d = dl
                        if (buffering && d != null) stringResource(R.string.downloading_pct, (d * 100).toInt())
                        else "${f.name.substringAfterLast('.', "").uppercase()} · ${formatBytes(f.sizeBytes)} · " + stringResource(R.string.saved_messages)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = vm::toggleFav) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (fav) stringResource(R.string.fav_remove) else stringResource(R.string.fav_add),
                    tint = if (fav) cs.primary else cs.onSurface
                )
            }
        }

        // Retomado (spec 9a): avisa y deja empezar de cero.
        val resumed by p.resumedFrom.collectAsStateWithLifecycle()
        if (resumed > 0) {
            AssistChip(
                onClick = { p.seekTo(0) },
                label = { Text(stringResource(R.string.resumed_at, fmtDur((resumed / 1000).toInt()))) },
                leadingIcon = { Icon(Icons.Filled.Replay, null) }
            )
        }
        // Seek.
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { p.seekTo((it * durMs).toLong()) }
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
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(cs.secondaryContainer)
                    .clickableNoRipple { p.prev() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp)) }
            Box(
                Modifier.weight(1f).height(if (landscape) 72.dp else 96.dp)
                    .clip(if (playing) MaterialTheme.shapes.large else CircleShape)
                    .background(if (playing) cs.primaryContainer else cs.primary)
                    .clickableNoRipple { p.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) stringResource(R.string.pause) else stringResource(R.string.play),
                    tint = if (playing) cs.onPrimaryContainer else cs.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(cs.secondaryContainer)
                    .clickableNoRipple { p.next() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp)) }
        }

        // Acciones.
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ActButton(
                active = repeat != RepeatMode.OFF,
                onClick = { p.cycleRepeat() },
                label = stringResource(when (repeat) { RepeatMode.ALL -> R.string.repeat_all; RepeatMode.ONE -> R.string.repeat_one; RepeatMode.OFF -> R.string.repeat_off }),
                icon = if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
            )
            ActButton(
                active = shuffle,
                onClick = { p.toggleShuffle() },
                label = stringResource(R.string.shuffle),
                icon = Icons.Filled.Shuffle
            )
            TextButton(onClick = { p.cycleSpeed() }) {
                Text(speedLb, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { vm.download(f) }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
            Box {
                IconButton(onClick = { p.cycleTimer() }) {
                    Icon(Icons.Filled.Timer, if (timer > 0) stringResource(R.string.sleep_timer_on, timer) else stringResource(R.string.sleep_timer_off), tint = if (timer > 0) cs.primary else cs.onSurface)
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
            stringResource(R.string.up_next, queue.size),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 32.dp, bottom = 4.dp)
        )
        queue.forEachIndexed { i, q ->
            val cur = i == index
            NimboListRow(
                title = q.showTitle(),
                subtitle = q.meta,
                leading = {
                    LeadingCircle(
                        if (q.kind == TgKind.VIDEO) Icons.Filled.Movie else Icons.Filled.MusicNote
                    )
                },
                onOpen = { p.playAt(i) },
                badge = {
                    if (cur) Icon(Icons.Filled.GraphicEq, stringResource(R.string.playing), tint = cs.primary)
                }
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    TopSnackBox(snack) {
        if (landscape) {
            Row(
                Modifier.fillMaxSize().background(cs.surface).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    artBox(if (isVideo) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    topBar()
                    Spacer(Modifier.height(8.dp))
                    panel()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().background(cs.surface).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                topBar()
                Spacer(Modifier.height(8.dp))
                artBox(Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                panel()
            }
        }
    }

    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            icon = { Icon(Icons.Filled.Info, null) },
            title = { Text(f.name) },
            text = {
                Text(
                    (if (isVideo) stringResource(R.string.kind_video) else stringResource(R.string.tab_audio)) +
                        (if (f.dur.isNotEmpty()) " · ${f.dur}" else "") +
                        " · ${formatBytes(f.sizeBytes)} · " + stringResource(R.string.details_body)
                )
            },
            confirmButton = {
                TextButton(onClick = { details = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.delete_from_tg_title)) },
            text = { Text(stringResource(R.string.delete_from_tg_body, f.showTitle())) },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(f, onClose)
                }) { Text(stringResource(R.string.delete)) }
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
    val source = remember { MutableInteractionSource() }
    return clickable(interactionSource = source, indication = null, onClick = onClick)
}
