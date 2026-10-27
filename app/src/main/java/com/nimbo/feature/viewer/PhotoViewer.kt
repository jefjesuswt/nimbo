package com.nimbo.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.encrypted
import com.nimbo.data.cloud.showTitle
import com.nimbo.ui.common.LocalArtwork
import com.nimbo.ui.common.TopSnackBox
import com.nimbo.ui.common.shareImage
import kotlinx.coroutines.delay

// Visor: una foto con zoom suave + filmstrip abajo (estilo iPhone).
// Sin pager: arrastrar con zoom ya no cambia de foto.
// Chrome por niveles: 0 full → 1 tenue → 2 oculto (tap en foto cicla,
// el tenue se oculta solo a los 3s, tocar el strip lo reactiva).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(vm: PhotoViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val files by vm.files.collectAsStateWithLifecycle()
    val startIndex = vm.startIndex
    val thumbsRepo = LocalArtwork.current.thumbs
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is PhotoEvent.Message -> snack.showSnackbar(e.text.resolve(res))
                is PhotoEvent.Share -> shareImage(ctx, e.path, e.name)
            }
        }
    }
    // Se borró todo lo que había (p. ej. desde otra pestaña): fuera.
    if (files.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var idx by remember(startIndex) { mutableStateOf(startIndex.coerceIn(files.indices)) }
    val cur = idx.coerceIn(files.indices)
    var tier by remember { mutableStateOf(0) }
    var grid by remember { mutableStateOf(false) }
    // Back cierra la galería antes que el visor (el route cierra el visor).
    BackHandler(enabled = grid) { grid = false }
    if (tier == 1) {
        LaunchedEffect(tier, idx) {
            delay(3000)
            tier = 2
        }
    }
    val chromeAlpha by animateFloatAsState(if (tier == 2) 0f else if (tier == 1) 0.35f else 1f, label = "chrome")
    val stripScale by animateFloatAsState(if (tier == 0) 1.06f else 1f, label = "strip")
    val bgScale by animateFloatAsState(if (tier == 0 && files.size > 1) 0.94f else 1f, label = "bg")
    val stripState = rememberLazyListState()
    if (stripState.isScrollInProgress) {
        LaunchedEffect(stripState.isScrollInProgress) { tier = 0 }
    }
    // Precarga thumbs del strip: sin números, todas con preview.
    LaunchedEffect(files) {
        files.forEach { f ->
            if (f.thumbFileId != 0) thumbsRepo.fetch(f.thumbFileId)
            if (f.kind == TgKind.IMAGE && f.fileId != 0 && !f.encrypted()) thumbsRepo.fetch(f.fileId)
        }
    }

    TopSnackBox(snack, Modifier.background(Color.Black)) {
        if (grid) {
            // Galería estilo Photos: grilla cuadrada de la carpeta.
            val thumbs by thumbsRepo.maps.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { grid = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                    Text(
                        pluralStringResource(R.plurals.photos_count, files.size, files.size),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(files) { i, f ->
                        val t = thumbs[f.thumbFileId] ?: thumbs[f.fileId]
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i == cur) Color.White.copy(alpha = 0.30f)
                                    else Color.White.copy(alpha = 0.10f)
                                )
                                .clickable {
                                    idx = i
                                    tier = 0
                                    grid = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        } else {
        Box(
            Modifier.fillMaxSize().graphicsLayer(scaleX = bgScale, scaleY = bgScale),
            contentAlignment = Alignment.Center
        ) {
            ZoomablePhoto(
                f = files[cur],
                load = vm::full,
                onTap = { tier = if (tier == 0) 1 else if (tier == 1) 2 else 0 }
            )
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().alpha(chromeAlpha)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(
                        files.getOrNull(cur)?.showTitle().orEmpty(),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.position, cur + 1, files.size),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = { grid = true }) {
                    Icon(Icons.Filled.GridView, stringResource(R.string.gallery), tint = Color.White)
                }
                IconButton(onClick = {
                    files.getOrNull(cur)?.let(vm::share)
                }) { Icon(Icons.Filled.Share, stringResource(R.string.share), tint = Color.White) }
                IconButton(onClick = {
                    files.getOrNull(cur)?.let(vm::download)
                }) { Icon(Icons.Filled.Download, stringResource(R.string.download), tint = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            if (files.size > 1 && tier < 2) {
                val thumbs by thumbsRepo.maps.collectAsStateWithLifecycle()
                // Tenue: chiquitos y pegaditos. Activo: grandes y separados.
                val big = tier == 0
                val cell by animateDpAsState(
                    if (big) 64.dp else 40.dp, label = "cell"
                )
                val gap by animateDpAsState(
                    if (big) 8.dp else 3.dp, label = "gap"
                )
                LazyRow(
                    state = stripState,
                    modifier = Modifier.fillMaxWidth()
                        .alpha(chromeAlpha)
                        .graphicsLayer(scaleX = stripScale, scaleY = stripScale)
                        .padding(bottom = 20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    itemsIndexed(files) { i, f ->
                        val sel = i == cur
                        Box(
                            Modifier.size(cell)
                                .clip(RoundedCornerShape(if (big) 10.dp else 6.dp))
                                .background(Color.White.copy(alpha = if (sel) 0.30f else 0.12f))
                                .clickable {
                                    idx = i
                                    tier = 0
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val t = thumbs[f.thumbFileId] ?: thumbs[f.fileId]
                            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePhoto(f: TgFile, load: suspend (TgFile) -> ImageBitmap?, onTap: () -> Unit) {
    var bmp by remember(f.messageId) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(f.messageId) { mutableStateOf(true) }
    var scale by remember(f.messageId) { mutableStateOf(1f) }
    var offset by remember(f.messageId) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(f.messageId) {
        loading = true
        bmp = load(f)
        loading = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bmp!!, null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                    .transformable(
                        // Amortiguado pero con recorrido: ni snappy ni lento.
                        state = rememberTransformableState { zoom, pan, _ ->
                            scale = (scale * (1 + (zoom - 1) * 0.85f)).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan * 2.4f else Offset.Zero
                        }
                    ),
                contentScale = ContentScale.Fit
            )
        } else if (loading) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
