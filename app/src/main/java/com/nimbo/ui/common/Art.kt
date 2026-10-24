package com.nimbo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.media.CoverRepository
import com.nimbo.data.media.ThumbRepository
import com.nimbo.data.media.miniThumb
import javax.inject.Inject
import javax.inject.Singleton

// Thumbs + carátulas para cualquier fila/tarjeta (lista, grid, álbumes,
// minibar, player). Lo provee MainActivity (como el ImageLoader de Coil).
@Singleton
class Artwork @Inject constructor(val thumbs: ThumbRepository, val covers: CoverRepository)

val LocalArtwork = staticCompositionLocalOf<Artwork> { error("Artwork no provisto") }

// Arte de un archivo: thumb del servidor → carátula embebida (hash) →
// minithumb del mensaje. Pide lo que falte.
@Composable
fun fileArt(f: TgFile, mini: Boolean = true): ImageBitmap? {
    val art = LocalArtwork.current
    val thumbs by art.thumbs.maps.collectAsStateWithLifecycle()
    val covers by art.covers.covers.collectAsStateWithLifecycle()
    LaunchedEffect(f.thumbFileId) { art.thumbs.fetch(f.thumbFileId) }
    LaunchedEffect(f.messageId, f.coverHash) { art.covers.fetch(f) }
    val placeholder = remember(f.id, mini) { if (mini) miniThumb(f.thumbMini) else null }
    return thumbs[f.thumbFileId] ?: f.coverHash.takeIf { it.isNotEmpty() }?.let { covers[it] } ?: placeholder
}
