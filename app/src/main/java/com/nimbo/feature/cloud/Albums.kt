package com.nimbo.feature.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimbo.R
import com.nimbo.data.cloud.TgAlbum
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.albumInfo
import com.nimbo.data.cloud.albumSubtitle
import com.nimbo.ui.common.EmptyState
import com.nimbo.ui.common.fileArt

// Pestañas Audio / Fotos: álbumes (carpetas) en lista o grid. Tocar abre
// sus archivos en FilesView. Contador arriba a la izquierda, estilo Mihon.
@Composable
fun AlbumsView(albums: List<TgAlbum>, audio: Boolean, grid: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (albums.isEmpty()) {
        EmptyState(
            icon = if (audio) Icons.Filled.MusicNote else Icons.Filled.Photo,
            title = if (audio) stringResource(R.string.no_audio) else stringResource(R.string.no_photos),
            body = stringResource(R.string.upload_from_files)
        )
        return
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(albums, key = { "a" + it.folder }) { a ->
                Card(
                    onClick = { onOpen(a.folder) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest)
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        AlbumArt(a, audio, Modifier.fillMaxSize(), 48)
                        Text(
                            "${a.files.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onPrimary,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .clip(MaterialTheme.shapes.small).background(cs.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Column(Modifier.padding(12.dp)) {
                        Text(a.name.ifEmpty { stringResource(R.string.unsorted) }, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        AlbumLines(a, audio)
                    }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(albums, key = { "a" + it.folder }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(a.folder) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) { AlbumArt(a, audio, Modifier.fillMaxSize(), 28) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name.ifEmpty { stringResource(R.string.unsorted) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        AlbumLines(a, audio)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlbumLines(a: TgAlbum, audio: Boolean) {
    val cs = MaterialTheme.colorScheme
    val sub = albumSubtitle(a, audio)
    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val counts = if (audio) listOf(pluralStringResource(R.plurals.tracks, a.files.size, a.files.size))
    else {
        val vids = a.files.count { it.kind == TgKind.VIDEO }
        val pics = a.files.size - vids
        listOfNotNull(
            if (pics > 0) pluralStringResource(R.plurals.photos_count, pics, pics) else null,
            if (vids > 0) pluralStringResource(R.plurals.videos_count, vids, vids) else null
        )
    }
    Text((counts + albumInfo(a, audio)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// Portada: audio = arte de la primera pista que lo tenga (embebido o
// cover.jpg de la carpeta); fotos = la más reciente. Si no, icono.
@Composable
private fun AlbumArt(a: TgAlbum, audio: Boolean, modifier: Modifier, iconDp: Int) {
    val bmp = albumArt(a, audio)
    if (bmp != null) Image(bmp, null, modifier, contentScale = ContentScale.Crop)
    else Icon(
        if (audio) Icons.Filled.Album else Icons.Filled.PhotoLibrary, null,
        Modifier.size(iconDp.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun albumArt(a: TgAlbum, audio: Boolean): ImageBitmap? {
    val rep = (if (audio) a.files.firstOrNull { it.thumbFileId != 0 || it.coverHash.isNotEmpty() } else null)
        ?: a.files.firstOrNull() ?: return null
    return fileArt(rep)
}
