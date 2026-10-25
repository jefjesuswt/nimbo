package com.nimbo.feature.player

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.data.cloud.showTitle
import com.nimbo.ui.common.fileArt

// Minibar diseño usuario: cover + título/artista + prev/play/next.
// Sin barra, sin cerrar. Solo cuando el sheet está cerrado.
@Composable
fun MiniPlayer(vm: PlayerViewModel, onOpenSheet: () -> Unit) {
    val p = vm.player
    val track by p.track.collectAsStateWithLifecycle()
    val playing by p.playing.collectAsStateWithLifecycle()
    val f = track ?: return
    val cs = MaterialTheme.colorScheme
    val art = fileArt(f)
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
            IconButton(onClick = { p.prev() }) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = cs.onTertiaryContainer)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { p.toggle() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = cs.onPrimary
                    )
                }
            }
            IconButton(onClick = { p.next() }) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = cs.onTertiaryContainer)
            }
        }
    }
}
