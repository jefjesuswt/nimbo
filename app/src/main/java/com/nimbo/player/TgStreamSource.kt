// Media3 marca DataSource como UnstableApi: opt-in por archivo.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nimbo.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nimbo.core.tdlib.TdClient
import com.nimbo.data.cloud.partAt
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// ponytail: DataSource es bloqueante por contrato (hilo de carga de
// ExoPlayer), de ahí runBlocking sobre el cliente suspendido.
// Streaming de vídeo (spec 08): `tg://file/<fileId>?total=<bytes>`.
// Pide a TDLib descargar DESDE la posición que pide ExoPlayer (seek = nuevo
// offset, no esperar desde el inicio) y lee con ReadFilePart en tramos de
// 512 KB. Sin bytes en 30 s → IOException (ExoPlayer reintenta).
class TgStreamSource(private val client: TdClient) : DataSource {
    private var uri: Uri? = null
    private var fileId = 0
    private var pos = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var buf = ByteArray(0)
    private var bufStart = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("bad uri")
        val total = dataSpec.uri.getQueryParameter("total")?.toLongOrNull() ?: 0L
        pos = dataSpec.position
        end = if (total > 0) total else C.LENGTH_UNSET.toLong()
        buf = ByteArray(0)
        bufStart = 0L
        runBlocking { client.send(TdApi.DownloadFile(fileId, 32, pos, 0, false)) }
        if (total <= 0) return C.LENGTH_UNSET.toLong()
        val left = (total - pos).coerceAtLeast(0L)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, left) else left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end > 0 && pos >= end) return C.RESULT_END_OF_INPUT
        if (pos < bufStart || pos >= bufStart + buf.size) fill()
        val from = (pos - bufStart).toInt()
        val n = minOf(length, buf.size - from)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(buf, from, buffer, offset, n)
        pos += n
        return n
    }

    // Espera a tener el tramo (o al menos 64 KB) desde pos y lo lee.
    private fun fill() {
        val want = if (end > 0) minOf(CHUNK, end - pos) else CHUNK
        val have = runBlocking {
            withTimeoutOrNull(30_000) {
                var h = 0L
                while (true) {
                    h = (client.send(TdApi.GetFileDownloadedPrefixSize(fileId, pos))
                        as? TdApi.FileDownloadedPrefixSize)?.size ?: 0L
                    if (h >= want || h >= MIN_READ) break
                    delay(100)
                }
                h
            }
        } ?: throw IOException("stalled")
        val part = runBlocking { client.send(TdApi.ReadFilePart(fileId, pos, minOf(have, want))) }
            as? TdApi.Data ?: throw IOException("read")
        if (part.data.isEmpty()) throw IOException("empty")
        buf = part.data
        bufStart = pos
    }

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun getUri(): Uri? = uri
    override fun close() {
        buf = ByteArray(0)
    }

    private companion object {
        const val CHUNK = 512L * 1024
        const val MIN_READ = 64L * 1024
    }
}

// Archivo troceado (spec 13): `tg://parts?f=<fileId>:<bytes>,…`. Un solo
// stream para ExoPlayer: el offset global elige la parte (partAt) y al
// acabar una sigue con la siguiente, así el seek vale en cualquier punto.
class ConcatTgSource(private val client: TdClient) : DataSource {
    private var uri: Uri? = null
    private var ids = emptyList<Int>()
    private var sizes = emptyList<Long>()
    private var idx = 0
    private var cur: TgStreamSource? = null
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ps = runCatching {
            dataSpec.uri.getQueryParameter("f")!!.split(',').map { p -> p.split(':').let { it[0].toInt() to it[1].toLong() } }
        }.getOrNull() ?: throw IOException("bad uri")
        ids = ps.map { it.first }
        sizes = ps.map { it.second }
        val (i, off) = partAt(dataSpec.position, sizes)
        openPart(i, off)
        val avail = (sizes.sum() - dataSpec.position).coerceAtLeast(0L)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, avail) else avail
        return left
    }

    private fun openPart(i: Int, off: Long) {
        cur?.close()
        idx = i
        cur = if (i >= ids.size) null else TgStreamSource(client).also {
            it.open(DataSpec.Builder().setUri(Uri.parse("tg://file/${ids[i]}?total=${sizes[i]}")).setPosition(off).build())
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (left > 0) {
            val s = cur ?: break
            val n = s.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n != C.RESULT_END_OF_INPUT) {
                left -= n
                return n
            }
            openPart(idx + 1, 0)
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun getUri(): Uri? = uri
    override fun close() {
        cur?.close()
        cur = null
    }
}

// tg:// → streaming TDLib; el resto (archivo local, subtítulos) → normal.
class TgOrLocalSource(private val local: DataSource, private val client: TdClient) : DataSource {
    private var cur: DataSource? = null
    override fun open(dataSpec: DataSpec): Long {
        val s = when {
            dataSpec.uri.scheme != "tg" -> local
            dataSpec.uri.host == "parts" -> ConcatTgSource(client)
            else -> TgStreamSource(client)
        }
        cur = s
        return s.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        cur?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    override fun addTransferListener(transferListener: TransferListener) = local.addTransferListener(transferListener)
    override fun getUri(): Uri? = cur?.uri
    override fun close() {
        cur?.close()
        cur = null
    }
}
