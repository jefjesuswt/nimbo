package com.nimbo.ui.telegram

import androidx.compose.ui.res.stringResource
import com.nimbo.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Abre el sheet desde cualquier tab. Lo registra TelegramRoute.
// State machine: sheet abierto ⟺ minibar oculto.
object PlayerNav {
    var openSheet: (() -> Unit)? = null
    var pending: Boolean = false
    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen

    fun setSheet(open: Boolean) {
        _sheetOpen.value = open
    }
}

// Minibar diseño usuario: cover + título/artista + prev/play/next.
// Sin barra, sin cerrar. Solo cuando el sheet está cerrado.
@Composable
fun MiniPlayer(onOpenSheet: () -> Unit) {
    val track by TgPlayer.track.collectAsState()
    val playing by TgPlayer.playing.collectAsState()
    val sheetOpen by PlayerNav.sheetOpen.collectAsState()
    val f = track
    if (f == null || sheetOpen) return
    val cs = MaterialTheme.colorScheme
    val thumbs by TgThumbs.maps.collectAsState()
    val covers by TgCovers.covers.collectAsState()
    val art = thumbs[f.thumbFileId]
        ?: f.coverHash.takeIf { it.isNotEmpty() }?.let { covers[it] }
        ?: remember(f.id) {
            f.thumbMini.takeIf { it.isNotEmpty() }?.let(TgThumbs::mini)
        }
    LaunchedEffect(f.thumbFileId) { TgThumbs.fetch(f.thumbFileId) }
    LaunchedEffect(f.messageId, f.coverHash) {
        TgCovers.fetch(f, TgRepo::noteCover)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
                    .clickable { onOpenSheet() },
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = cs.onSurfaceVariant)
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp)
                    .clickable { onOpenSheet() }
            ) {
                Text(f.showTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
                Text(
                    f.artist.ifEmpty { stringResource(R.string.streaming_from_tg) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = { TgPlayer.prev() }) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = cs.onTertiaryContainer)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { TgPlayer.toggle() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = cs.onPrimary
                    )
                }
            }
            IconButton(onClick = { TgPlayer.next() }) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = cs.onTertiaryContainer)
            }
        }
    }
}
