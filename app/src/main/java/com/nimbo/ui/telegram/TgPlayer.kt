// Media3 marca PlayerView/DataSource/etc. como UnstableApi: opt-in por archivo.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nimbo.ui.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// Puras del prototipo (ciclos repeat/speed/timer). Testeables.
// Desde dónde retomar: nada en cortos (< 3 min) ni si casi terminó (< 20 s).
fun resumeFrom(posMs: Long, durMs: Long): Long = when {
    posMs <= 0L -> 0L
    durMs in 1 until 3 * 60_000L -> 0L
    durMs > 0 && durMs - posMs < 20_000L -> 0L
    else -> posMs
}

fun nextRepeat(cur: String): String = when (cur) {
    "off" -> "all"
    "all" -> "one"
    else -> "off"
}

fun nextSpeed(cur: Float): Float = when (cur) {
    1f -> 1.25f
    1.25f -> 1.5f
    1.5f -> 2f
    else -> 1f
}

fun nextTimer(cur: Int): Int = when (cur) {
    0 -> 5
    5 -> 15
    15 -> 30
    else -> 0
}

// Glyph por nombre (prototipo artGlyph). Puro, testeable.
fun artGlyphFor(name: String): String {
    val n = name.lowercase()
    return when {
        "podcast" in n || "interview" in n -> "podcasts"
        "voice" in n || "memo" in n -> "mic"
        "lecture" in n || "class" in n -> "school"
        else -> "music_note"
    }
}

// Descarga completa compartida (player + descargas): espera el archivo,
// reporta 0..1, devuelve path local o null. Una sola espera, sin duplicar.
object TgDownload {
    // Descargas completas en vuelo: las covers por rango no deben
    // pedir el mismo archivo (TDLib cancela una con la otra).
    private val fullActive = mutableSetOf<Int>()
    fun isFullActive(id: Int): Boolean = synchronized(fullActive) { id in fullActive }

    suspend fun awaitFile(
        fileId: Int,
        timeoutMs: Long = 180_000,
        report: (Float) -> Unit = {}
    ): String? {
        if (fileId == 0) return null
        fun ok(f: TdApi.File?): String? {
            val p = f?.local?.path?.takeIf { it.isNotEmpty() } ?: return null
            return if (java.io.File(p).exists()) p else null
        }
        val dlRes = TgClient.sendAwait(TdApi.DownloadFile(fileId, 32, 0, 0, true))
        (dlRes as? TdApi.Error)?.let {
            android.util.Log.w("TgPlay", "dl $fileId ERR ${it.code} ${it.message}")
        }
        (dlRes as? TdApi.File)?.let { f ->
            if (f.local.isDownloadingCompleted) return ok(f)
        }
        report(0f)
        synchronized(fullActive) { fullActive.add(fileId) }
        try {
            val path = withTimeoutOrNull(timeoutMs) {
            TgClient.updates.first { u ->
                val rf = (u as? TdApi.UpdateFile)?.file ?: return@first false
                if (rf.id != fileId) return@first false
                val tot = rf.size.takeIf { it > 0 } ?: rf.expectedSize.takeIf { it > 0 } ?: 0L
                val got = maxOf(rf.local.downloadedSize, rf.local.downloadedPrefixSize)
                if (tot > 0) report((got.toFloat() / tot).coerceIn(0f, 1f))
                rf.local.isDownloadingCompleted
            }.let { (it as TdApi.UpdateFile).file.local.path }
        }?.takeIf { it.isNotEmpty() }
        if (path != null && java.io.File(path).exists()) return path
        // Ya estaba en disco (sin eventos nuevos).
        val cur = TgClient.sendAwait(TdApi.GetFile(fileId)) as? TdApi.File
        return ok(cur)
        } finally {
            synchronized(fullActive) { fullActive.remove(fileId) }
        }
    }
}

// DataSource TDLib: `tg://file/<fileId>?total=<bytes>`. Descarga el rango
// pedido y lee del parcial; si TDLib no avanza en 30s → IOException
// (ExoPlayer reintenta y muestra buffering). El temp de TDLib ES el caché.
class TgStreamSource : DataSource {
    private var raf: RandomAccessFile? = null
    private var fileId: Int = 0
    private var pos: Long = 0

    @Volatile private var complete: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        fileId = uri.lastPathSegment?.toIntOrNull() ?: throw IOException("bad uri")
        val total: Long = uri.getQueryParameter("total")?.toLongOrNull() ?: 0L
        pos = dataSpec.position
        runBlocking {
            val res = TgClient.sendAwait(TdApi.DownloadFile(fileId, 32, 0, 0, true))
            val path = (res as? TdApi.File)?.local?.path?.takeIf { it.isNotEmpty() }
                ?: throw IOException("tdlib")
            raf = RandomAccessFile(path, "r")
            if (!waitAvailable(pos + 1)) throw IOException("timeout")
        }
        return if (total > 0) (total - pos).coerceAtLeast(0L) else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        val need = pos + length
        waitAvailable(need)
        val avail = runCatching { r.length() }.getOrDefault(0L) - pos
        if (avail <= 0) {
            // Fin real → EOF; si no, stall (ExoPlayer reintenta).
            if (complete) return C.RESULT_END_OF_INPUT
            throw IOException("stalled")
        }
        val n = r.read(buffer, offset, avail.coerceAtMost(length.toLong()).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        return n
    }

    // Espera a que haya `need` bytes (o fin). True si se puede leer algo.
    private fun waitAvailable(need: Long): Boolean {
        val r = raf ?: return false
        if (runCatching { r.length() }.getOrDefault(0L) >= need) return true
        runBlocking {
            withTimeoutOrNull(30_000) {
                while (true) {
                    val cur = TgClient.sendAwait(TdApi.GetFile(fileId)) as? TdApi.File
                        ?: break
                    if (cur.local.isDownloadingCompleted) {
                        complete = true
                        break
                    }
                    if (cur.local.downloadedPrefixSize >= need) break
                    delay(500)
                }
            }
        }
        return true
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Sin telemetría v1.
    }

    override fun getUri(): Uri? = null
    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }
}

// Player único (UI + servicio comparten instancia, mismo proceso).
object TgPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var app: Context? = null
    private var listenerOn = false
    private var ticker: Job? = null
    private var timerJob: Job? = null

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
    private val _repeat = MutableStateFlow("off")
    val repeat: StateFlow<String> = _repeat
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed
    private val _timer = MutableStateFlow(0)
    val timer: StateFlow<Int> = _timer
    private val _favs = MutableStateFlow<Set<String>>(emptySet())
    val favs: StateFlow<Set<String>> = _favs
    // Descarga del track actual 0..1 (null = sin descarga).
    // Posición desde la que se retomó el track actual (0 = desde el inicio).
    private val _resumedFrom = MutableStateFlow(0L)
    val resumedFrom: StateFlow<Long> = _resumedFrom
    private val _dlProgress = MutableStateFlow<Float?>(null)
    val dlProgress: StateFlow<Float?> = _dlProgress
    private var dlJob: Job? = null

    fun init(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            _favs.value = ctx.applicationContext
                .getSharedPreferences("nimbo", Context.MODE_PRIVATE)
                .getStringSet("tg_favs", emptySet()).orEmpty()
        }
    }

    // Instancia para PlayerView / servicio (mismo proceso).
    val exo: ExoPlayer?
        get() = player

    fun ensure(): ExoPlayer {
        player?.let { return it }
        val ctx = app ?: throw IllegalStateException("TgPlayer.init first")
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    androidx.media3.datasource.DefaultDataSource.Factory(ctx)
                )
            )
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        if (!listenerOn) {
            listenerOn = true
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(on: Boolean) {
                    _playing.value = on
                    if (!on) savePos()
                    if (on) startTicker() else ticker?.cancel()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    _buffering.value = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        _durMs.value = p.duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) onTrackEnded()
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    // Nada: un solo item por vez, track/index los manda loadCurrent.
                }

                override fun onPlayerError(err: PlaybackException) {
                    _error.value = err.message
                    _playing.value = false
                }
            })
        }
        player = p
        return p
    }

    private fun onTrackEnded() {
        _track.value?.let { TgRepo.savePos(it.messageId, 0L) }
        val n = _queue.value.size
        if (n == 0) {
            _playing.value = false
            return
        }
        when (_repeat.value) {
            "one" -> {
                player?.seekTo(0)
                player?.playWhenReady = true
            }
            "all" -> playAt((_index.value + 1) % n)
            else -> if (_index.value < n - 1) playAt(_index.value + 1) else {
                _playing.value = false
            }
        }
    }

    private fun toItem(f: TgFile, path: String): MediaItem {
        val art: ByteArray? = f.coverHash.takeIf { it.isNotEmpty() }?.let { h ->
            java.io.File(app?.cacheDir, "tg-covers/$h.jpg").takeIf { it.exists() }
                ?.let { shrinkArt(it) }
        }
        val md = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(f.showTitle())
            .apply {
                f.artist.takeIf { it.isNotEmpty() }?.let { setArtist(it) }
                if (art != null) setArtworkData(
                    art,
                    androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }
            .build()
        return MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(java.io.File(path)))
            .setMediaId(f.messageId.toString())
            .setMediaMetadata(md)
            .build()
    }

    // Descarga completa → caché propia → play local (seek exacto).
    // Servicio normal (Media3 se pone foreground solo al sonar).
    fun play(queue: List<TgFile>, index: Int) {
        val ctx = app ?: throw IllegalStateException("TgPlayer.init first")
        // Servicio normal (Media3 se pone foreground solo al sonar).
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
        hasSubtitles = false
        _track.value = f
        _posMs.value = 0L
        _resumedFrom.value = 0L
        _durMs.value = 0L
        dlJob?.cancel()
        _buffering.value = true
        _dlProgress.value = 0f
        dlJob = scope.launch(Dispatchers.IO) {
            try {
                val src = TgDownload.awaitFile(f.fileId) { p -> _dlProgress.value = p }
                    ?: throw IOException("download")
                val dst = stageCopy(src, f)
                val start = resumeFrom(TgRepo.posOf(f.messageId), f.durSec * 1000L)
                withContext(Dispatchers.Main) {
                    if (_track.value?.messageId != f.messageId) return@withContext
                    val p = ensure()
                    p.setMediaItem(toItem(f, dst), start)
                    _resumedFrom.value = start
                    p.setPlaybackSpeed(_speed.value)
                    p.prepare()
                    p.playWhenReady = true
                }
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
        val ctx = app ?: throw IllegalStateException("TgPlayer.init first")
        val dir = java.io.File(ctx.cacheDir, "tg-player").apply { mkdirs() }
        val ext = f.name.substringAfterLast('.', "mp3")
        val dst = java.io.File(dir, "${f.messageId}.$ext")
        if (!dst.exists()) java.io.File(src).copyTo(dst, overwrite = true)
        trimCache(dir, 1L shl 30)
        return dst.absolutePath
    }

    // Arte chico para notificación (la de 520KB la tumba). Pura IO.
    private fun shrinkArt(file: java.io.File): ByteArray? = runCatching {
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, b)
        var s = 1
        while (b.outWidth / s > 256 || b.outHeight / s > 256) s *= 2
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
            ?: return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }.getOrNull()

    private fun trimCache(dir: java.io.File, cap: Long) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= cap) break
            total -= f.length()
            f.delete()
        }
    }

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
    @Volatile var hasSubtitles = false
        private set

    fun setSubtitles(path: String, vtt: Boolean) {
        val p = player ?: return
        val cur = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(java.io.File(path)))
            .setMimeType(if (vtt) androidx.media3.common.MimeTypes.TEXT_VTT else androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        p.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), pos)
        p.prepare()
        hasSubtitles = true
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
        TgRepo.savePos(f.messageId, p.currentPosition.coerceAtLeast(0L))
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

    fun toggleFav() {
        val id = _track.value?.id ?: return
        val cur = _favs.value.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        _favs.value = cur
        app?.getSharedPreferences("nimbo", Context.MODE_PRIVATE)?.edit {
            putStringSet("tg_favs", cur)
        }
    }

    fun stop() {
        savePos()
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
        runCatching {
            app?.cacheDir?.let { java.io.File(it, "tg-player").deleteRecursively() }
        }
        app?.stopService(Intent(app, PlaybackService::class.java))
    }

    fun release() {
        stop()
        player?.release()
        player = null
        listenerOn = false
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
}
