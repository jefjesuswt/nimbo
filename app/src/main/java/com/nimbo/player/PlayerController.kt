// Media3 marca ExoPlayer/DataSource como UnstableApi: opt-in por archivo.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nimbo.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.util.trimCache
import com.nimbo.data.cloud.PlaybackPositions
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.encrypted
import com.nimbo.data.cloud.incomplete
import com.nimbo.data.crypto.EncBlockLog
import com.nimbo.data.crypto.KeyRing
import com.nimbo.data.crypto.cipherSize
import com.nimbo.data.transfer.PlainFiles
import com.nimbo.data.cloud.isSplit
import com.nimbo.data.cloud.showTitle
import com.nimbo.data.media.CoverRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// Player único (UI + servicio comparten instancia, mismo proceso). Todo lo
// que toca ExoPlayer corre en el hilo principal.
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val client: TdClient,
    private val plain: PlainFiles,
    private val keys: KeyRing,
    private val positions: PlaybackPositions,
    private val covers: CoverRepository,
    private val prefs: AppPrefs,
    @ApplicationScope appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private var timerJob: Job? = null
    private var dlJob: Job? = null

    private val _track = MutableStateFlow<TgFile?>(null)
    val track: StateFlow<TgFile?> = _track
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _posMs = MutableStateFlow(0L)
    val posMs: StateFlow<Long> = _posMs
    private val _durMs = MutableStateFlow(0L)
    val durMs: StateFlow<Long> = _durMs
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _queue = MutableStateFlow<List<TgFile>>(emptyList())
    val queue: StateFlow<List<TgFile>> = _queue
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index
    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed
    private val _timer = MutableStateFlow(0)
    val timer: StateFlow<Int> = _timer
    private val _favs = MutableStateFlow(prefs.favs)
    val favs: StateFlow<Set<String>> = _favs
    // Posición desde la que se retomó el track actual (0 = desde el inicio).
    private val _resumedFrom = MutableStateFlow(0L)
    val resumedFrom: StateFlow<Long> = _resumedFrom
    // Descarga del track actual 0..1 (null = sin descarga).
    private val _dlProgress = MutableStateFlow<Float?>(null)
    val dlProgress: StateFlow<Float?> = _dlProgress
    // Subtítulo lateral cargado en el item actual (spec 08).
    private val _hasSubtitles = MutableStateFlow(false)
    val hasSubtitles: StateFlow<Boolean> = _hasSubtitles
    // Pistas de audio y texto del item actual (embebidas + subtítulo lateral).
    private val _tracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val tracks: StateFlow<List<TrackOption>> = _tracks

    // Descarga en curso del vídeo en streaming: se corta al cambiar o parar.
    private var streamingIds = emptyList<Int>()

    init {
        // Cerrar sesión: nada de la cuenta anterior sigue sonando.
        scope.launch {
            var wasReady = false
            client.auth.collect { a ->
                if (a == TgAuthState.READY) wasReady = true
                else if (wasReady) {
                    wasReady = false
                    stop()
                }
            }
        }
    }

    // Instancia para PlayerView / servicio (mismo proceso).
    val exo: ExoPlayer?
        get() = player

    fun ensure(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx)
            // DefaultMediaSourceFactory: progresivo + subtítulos laterales.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ctx).setDataSourceFactory {
                    TgOrLocalSource(DefaultDataSource.Factory(ctx).createDataSource(), client) { keys.key.value }
                }
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(on: Boolean) {
                _playing.value = on
                if (!on) savePos()
                if (on) startTicker() else ticker?.cancel()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) _durMs.value = p.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) onTrackEnded()
            }

            override fun onTracksChanged(t: Tracks) {
                _tracks.value = trackOptions(t)
            }

            override fun onPlayerError(err: PlaybackException) {
                _error.value = err.message
                _playing.value = false
            }
        })
        player = p
        return p
    }

    private fun onTrackEnded() {
        _track.value?.let { positions.save(it.messageId, 0L) }
        val n = _queue.value.size
        if (n == 0) {
            _playing.value = false
            return
        }
        when (_repeat.value) {
            RepeatMode.ONE -> {
                player?.seekTo(0)
                player?.playWhenReady = true
            }
            RepeatMode.ALL -> playAt((_index.value + 1) % n)
            RepeatMode.OFF -> if (_index.value < n - 1) playAt(_index.value + 1) else _playing.value = false
        }
    }

    private fun toItem(f: TgFile, uri: Uri, art: ByteArray?): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(f.showTitle())
            .apply {
                f.artist.takeIf { it.isNotEmpty() }?.let { setArtist(it) }
                if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(f.messageId.toString())
            .setMediaMetadata(md)
            .build()
    }

    // Audio: descarga completa → caché propia → play local (seek exacto).
    // Vídeo: streaming. Servicio normal (Media3 se pone en primer plano al sonar).
    fun play(queue: List<TgFile>, index: Int) {
        if (queue.isEmpty()) return
        ctx.startService(Intent(ctx, PlaybackService::class.java))
        ensure()
        _error.value = null
        _queue.value = queue
        _index.value = index.coerceIn(queue.indices)
        loadCurrent()
    }

    private fun loadCurrent() {
        val f = _queue.value.getOrNull(_index.value) ?: return
        savePos()
        _hasSubtitles.value = false
        _track.value = f
        _posMs.value = 0L
        _resumedFrom.value = 0L
        _durMs.value = 0L
        dlJob?.cancel()
        _buffering.value = true
        _dlProgress.value = 0f
        cancelStream()
        dlJob = scope.launch {
            try {
                val art = withContext(io) {
                    f.coverHash.takeIf { it.isNotEmpty() }?.let { covers.file(it) }?.takeIf { it.exists() }?.let(::shrinkArt)
                }
                val start = resumeFrom(positions.of(f.messageId), f.durSec * 1000L)
                // Vídeo: streaming (arranca con los primeros MB, seek = nuevo
                // offset). Audio: descarga completa (chico, seek exacto).
                if (f.locked) throw IOException("locked")
                // Cifrado (spec 12): DecryptSource encima; total = bytes cifrados.
                val enc = if (f.encrypted()) "&enc=1" else ""
                val uri = if (f.isSplit()) {
                    // Troceado (spec 13): siempre streaming, parte a parte.
                    if (f.incomplete()) throw IOException("incomplete")
                    _dlProgress.value = null
                    streamingIds = f.parts.map { it.fileId }
                    Uri.parse("tg://parts?f=" + f.parts.joinToString(",") { "${it.fileId}:${it.size}" } + enc)
                } else if (f.kind == TgKind.VIDEO && f.fileId != 0 && f.sizeBytes > 0) {
                    _dlProgress.value = null
                    streamingIds = listOf(f.fileId)
                    val total = if (f.encrypted()) cipherSize(f.sizeBytes, 1 shl EncBlockLog) else f.sizeBytes
                    Uri.parse("tg://file/${f.fileId}?total=$total$enc")
                } else {
                    val src = plain.path(f) { p -> _dlProgress.value = p } ?: throw IOException("download")
                    // Unido/descifrado ya vive en la caché de PlainFiles (se borra al bloquear).
                    val path = if (f.encrypted() || f.isSplit()) src else withContext(io) { stageCopy(src, f) }
                    Uri.fromFile(java.io.File(path))
                }
                if (_track.value?.messageId != f.messageId) return@launch
                val p = ensure()
                p.setMediaItem(toItem(f, uri, art), start)
                _resumedFrom.value = start
                p.setPlaybackSpeed(_speed.value)
                p.prepare()
                p.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
                _playing.value = false
                _buffering.value = false
            } finally {
                _dlProgress.value = null
            }
        }
    }

    // Copia a caché propia (vida clara: stop la borra, LRU 1GB).
    private fun stageCopy(src: String, f: TgFile): String {
        val dir = java.io.File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
        val ext = f.name.substringAfterLast('.', "mp3")
        val dst = java.io.File(dir, "${f.messageId}.$ext")
        if (!dst.exists()) java.io.File(src).copyTo(dst, overwrite = true)
        trimCache(dir, 1L shl 30)
        return dst.absolutePath
    }

    // Arte chico para la notificación (la de 520KB la tumba).
    private fun shrinkArt(file: java.io.File): ByteArray? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, b)
        var s = 1
        while (b.outWidth / s > 256 || b.outHeight / s > 256) s *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            ?: return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }.getOrNull()

    fun toggle() {
        val p = player ?: return
        if (_track.value == null) return
        p.playWhenReady = !p.playWhenReady
    }

    fun next() {
        val n = _queue.value.size
        if (n == 0) return
        if (_shuffle.value && n > 1) {
            var i = _index.value
            while (i == _index.value) i = (0 until n).random()
            playAt(i)
        } else {
            playAt((_index.value + 1) % n)
        }
    }

    fun prev() {
        val n = _queue.value.size
        if (n == 0) return
        playAt((_index.value - 1 + n) % n)
    }

    fun pause() {
        player?.playWhenReady = false
    }

    // Subtítulo lateral (spec 08): rearma el item actual con la pista,
    // conservando posición. Se selecciona solo (SELECTION_FLAG_DEFAULT).
    fun setSubtitles(path: String, vtt: Boolean) {
        val p = player ?: return
        val cur = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(java.io.File(path)))
            .setMimeType(if (vtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        p.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), pos)
        p.prepare()
        _hasSubtitles.value = true
    }

    // Fija una pista de audio o texto (spec 08b); el texto se enciende.
    fun selectTrack(o: TrackOption) {
        val p = player ?: return
        val g = p.currentTracks.groups.getOrNull(o.group) ?: return
        val type = if (o.type == TrackType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index))
            .build()
    }

    fun showSubtitles(on: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        _posMs.value = ms
        _resumedFrom.value = 0L
    }

    // Guarda la posición del track actual (pausa, cambio, stop, cada 10 s).
    private fun savePos() {
        val f = _track.value ?: return
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) return
        positions.save(f.messageId, p.currentPosition.coerceAtLeast(0L))
    }

    fun playAt(i: Int) {
        if (i !in _queue.value.indices) return
        _index.value = i
        _error.value = null
        loadCurrent()
    }

    fun cycleRepeat() {
        _repeat.value = nextRepeat(_repeat.value)
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
    }

    fun cycleSpeed() {
        _speed.value = nextSpeed(_speed.value)
        player?.setPlaybackSpeed(_speed.value)
    }

    fun cycleTimer() {
        timerJob?.cancel()
        _timer.value = nextTimer(_timer.value)
        val min = _timer.value
        if (min > 0) {
            timerJob = scope.launch {
                delay(min * 60 * 1000L)
                _timer.value = 0
                player?.playWhenReady = false
            }
        }
    }

    // Devuelve si quedó como favorito.
    fun toggleFav(): Boolean {
        val id = _track.value?.id ?: return false
        val cur = _favs.value.toMutableSet()
        val added = cur.add(id)
        if (!added) cur.remove(id)
        _favs.value = cur
        prefs.favs = cur
        return added
    }

    private fun cancelStream() {
        val ids = streamingIds
        if (ids.isEmpty()) return
        streamingIds = emptyList()
        scope.launch { ids.forEach { id -> runCatching { client.send(TdApi.CancelDownloadFile(id, false)) } } }
    }

    fun stop() {
        savePos()
        cancelStream()
        timerJob?.cancel()
        ticker?.cancel()
        dlJob?.cancel()
        player?.stop()
        player?.clearMediaItems()
        _track.value = null
        _playing.value = false
        _posMs.value = 0L
        _durMs.value = 0L
        _dlProgress.value = null
        _queue.value = emptyList()
        _index.value = 0
        _error.value = null
        // Nube-first: al dejar de escuchar se borra la caché.
        scope.launch(io) { runCatching { java.io.File(ctx.cacheDir, CACHE_DIR).deleteRecursively() } }
        ctx.stopService(Intent(ctx, PlaybackService::class.java))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var n = 0
            while (true) {
                val p = player ?: break
                _posMs.value = p.currentPosition.coerceAtLeast(0L)
                if (p.duration > 0) _durMs.value = p.duration
                if (++n % 20 == 0) savePos()
                delay(500)
            }
        }
    }

    private companion object {
        const val CACHE_DIR = "tg-player"
    }
}

enum class TrackType { AUDIO, TEXT }

// Pista elegible: grupo + índice en Tracks; label/idioma tal cual del archivo.
data class TrackOption(
    val type: TrackType, val group: Int, val index: Int,
    val label: String?, val lang: String?, val channels: Int, val selected: Boolean
)

private fun trackOptions(t: Tracks): List<TrackOption> = t.groups.flatMapIndexed { gi, g ->
    val type = when (g.type) {
        C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
        C.TRACK_TYPE_TEXT -> TrackType.TEXT
        else -> return@flatMapIndexed emptyList()
    }
    (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
        val f = g.getTrackFormat(i)
        TrackOption(type, gi, i, f.label, f.language, f.channelCount, g.isTrackSelected(i))
    }
}
