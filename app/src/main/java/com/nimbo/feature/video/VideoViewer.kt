// Media3 marca PlayerView/DataSource/etc. como UnstableApi: opt-in por archivo.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nimbo.feature.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nimbo.R
import com.nimbo.core.util.fmtDur
import com.nimbo.data.cloud.showTitle
import com.nimbo.player.PlayerController
import kotlinx.coroutines.delay

// ---- Puras (spec 08), testeables ----

// Seek con tope en [0, dur]; dur desconocido = sin tope superior.
fun seekTarget(posMs: Long, durMs: Long, deltaMs: Long): Long =
    (posMs + deltaMs).coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)

// Gesto vertical → nivel 0..1 (arrastrar hacia arriba sube).
fun dragLevel(start: Float, dyPx: Float, heightPx: Float): Float =
    if (heightPx <= 0f) start else (start - dyPx / heightPx).coerceIn(0f, 1f)

// Subtítulo hermano: "peli.<lang>.srt" > "peli.srt" > cualquier "peli.*.srt|vtt".
fun pickSubtitle(video: String, names: List<String>, lang: String): String? {
    val base = video.substringBeforeLast('.').lowercase()
    val subs = names.filter {
        val n = it.lowercase()
        (n.endsWith(".srt") || n.endsWith(".vtt")) &&
            n.substringBeforeLast('.').let { s -> s == base || s.startsWith("$base.") }
    }
    return subs.firstOrNull { it.lowercase().substringBeforeLast('.') == "$base.${lang.lowercase()}" }
        ?: subs.firstOrNull { it.lowercase().substringBeforeLast('.') == base }
        ?: subs.firstOrNull()
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

// Pantalla completa: gestos, velocidad, subtítulos, PiP. Streaming
// progresivo vía PlayerController (rangos TDLib).
@Composable
fun VideoViewer(vm: VideoViewModel, onClose: () -> Unit) {
    val p: PlayerController = vm.player
    val ctx = LocalContext.current
    val act = remember { ctx.activity() }
    val view = LocalView.current
    val track by p.track.collectAsStateWithLifecycle()
    val playing by p.playing.collectAsStateWithLifecycle()
    val posMs by p.posMs.collectAsStateWithLifecycle()
    val durMs by p.durMs.collectAsStateWithLifecycle()
    val buffering by p.buffering.collectAsStateWithLifecycle()
    val dl by p.dlProgress.collectAsStateWithLifecycle()
    val speed by p.speed.collectAsStateWithLifecycle()

    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    // Indicador central: "+20 s", "Brightness 60%"… (texto, se apaga solo).
    var hint by remember { mutableStateOf<String?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    var seekAcc by remember { mutableIntStateOf(0) }
    var subsOn by remember { mutableStateOf(true) }
    var subsLoaded by remember { mutableStateOf<Long?>(null) }
    val hasSubs by p.hasSubtitles.collectAsStateWithLifecycle()

    BackHandler { onClose() }

    // Arranque + entorno de pantalla completa; todo se restaura al cerrar.
    // PiP: lo informa la Activity; en < 12 se entra a mano al salir con
    // Home (12+ usa setAutoEnterEnabled).
    var pip by remember { mutableStateOf(false) }
    DisposableEffect(act) {
        val comp = act as? ComponentActivity
        val onPip = Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        val onLeave = Runnable {
            if (Build.VERSION.SDK_INT in 26..30 && p.playing.value) {
                act?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            }
        }
        comp?.addOnPictureInPictureModeChangedListener(onPip)
        comp?.addOnUserLeaveHintListener(onLeave)
        onDispose {
            comp?.removeOnPictureInPictureModeChangedListener(onPip)
            comp?.removeOnUserLeaveHintListener(onLeave)
        }
    }

    DisposableEffect(Unit) {
        vm.start()
        val win = act?.window
        val insets = win?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val oldBrightness = win?.attributes?.screenBrightness
        view.keepScreenOn = true
        onDispose {
            vm.stop()
            insets?.show(WindowInsetsCompat.Type.systemBars())
            win?.let { w -> w.attributes = w.attributes.apply { screenBrightness = oldBrightness ?: -1f } }
            view.keepScreenOn = false
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= 31) {
                act?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }

    // Vídeo horizontal → gira a horizontal solo (estilo YouTube); botón para volver.
    val exo = p.exo
    LaunchedEffect(track?.messageId, exo) {
        val p = exo ?: return@LaunchedEffect
        repeat(40) {
            val s = p.videoSize
            if (s.width > 0 && s.height > 0) {
                landscape = s.width > s.height
                act?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= 26) {
                    val r = Rational(s.width, s.height).let {
                        if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < 1 / 2.39f) Rational(100, 239) else it
                    }
                    val b = PictureInPictureParams.Builder().setAspectRatio(r)
                    // 12+: Home con vídeo sonando entra en PiP solo.
                    if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(true)
                    act?.setPictureInPictureParams(b.build())
                }
                return@LaunchedEffect
            }
            delay(250)
        }
    }

    // Subtítulo hermano (.srt/.vtt) del track actual.
    LaunchedEffect(track?.messageId, buffering) {
        val f = track ?: return@LaunchedEffect
        if (buffering || subsLoaded == f.messageId) return@LaunchedEffect
        subsLoaded = f.messageId
        val (path, vtt) = vm.subtitleFor(f) ?: return@LaunchedEffect
        p.setSubtitles(path, vtt)
        p.showSubtitles(subsOn)
    }

    // Segundo plano sin PiP (o PiP cerrado) = pausa.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) p.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Controles se ocultan a los 3 s sonando.
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(hintTick) {
        if (hint != null) {
            delay(800)
            hint = null
            seekAcc = 0
        }
    }

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableFloatStateOf(0f) }
    val brightnessLb = stringResource(R.string.brightness_pct)
    val volumeLb = stringResource(R.string.volume_pct)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = {
                it.player = exo
                it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        // Gestos: tap = controles; doble tap = ±10 s; arrastre vertical =
        // brillo (izquierda) / volumen (derecha).
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { o ->
                            val fwd = o.x > size.width / 2
                            seekAcc += if (fwd) 10 else -10
                            p.seekTo(seekTarget(p.posMs.value, p.durMs.value, if (fwd) 10_000 else -10_000))
                            hint = (if (seekAcc > 0) "+" else "") + "$seekAcc s"
                            hintTick++
                        }
                    )
                }
                .pointerInput(Unit) {
                    var left = true
                    var start = 0f
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            left = o.x < size.width / 2
                            dy = 0f
                            start = if (left) {
                                act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                            } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                        },
                        onVerticalDrag = { _, d ->
                            dy += d
                            level = dragLevel(start, dy, size.height.toFloat())
                            val pct = (level * 100).toInt()
                            if (left) {
                                act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) } }
                                hint = brightnessLb.format(pct)
                            } else {
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVol).toInt(), 0)
                                hint = volumeLb.format(pct)
                            }
                            hintTick++
                        }
                    )
                }
        )
        if (buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                dl?.let {
                    Text(
                        stringResource(R.string.downloading_pct, (it * 100).toInt()),
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        hint?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).clip(MaterialTheme.shapes.large)
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        // Controles (blanco sobre velo: convención de reproductores de vídeo).
        AnimatedVisibility(
            visible = controls && !pip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                    Text(
                        track?.showTitle().orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (subsLoaded != null && hasSubs) {
                        IconButton(onClick = { subsOn = !subsOn; p.showSubtitles(subsOn) }) {
                            Icon(
                                if (subsOn) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                                stringResource(R.string.subtitles), tint = Color.White
                            )
                        }
                    }
                    TextButton(onClick = { p.cycleSpeed() }) {
                        Text(if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×", color = Color.White)
                    }
                    IconButton(onClick = { fill = !fill }) {
                        Icon(Icons.Filled.AspectRatio, stringResource(if (fill) R.string.fit_screen else R.string.fill_screen), tint = Color.White)
                    }
                    IconButton(onClick = {
                        landscape = !landscape
                        act?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }) { Icon(Icons.Filled.ScreenRotation, stringResource(R.string.rotate), tint = Color.White) }
                    if (Build.VERSION.SDK_INT >= 26) {
                        IconButton(onClick = {
                            act?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                        }) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip), tint = Color.White) }
                    }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    IconButton(onClick = { p.prev() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(
                        onClick = { p.toggle() },
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.pause else R.string.play),
                            tint = Color.White, modifier = Modifier.size(48.dp)
                        )
                    }
                    IconButton(onClick = { p.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = drag ?: frac,
                        onValueChange = { drag = it; controls = true },
                        onValueChangeFinished = {
                            drag?.let { p.seekTo((it * durMs).toLong()) }
                            drag = null
                        }
                    )
                    Row {
                        Text(fmtDur((((drag ?: frac) * durMs) / 1000).toInt()), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(fmtDur((durMs / 1000).toInt()), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}
