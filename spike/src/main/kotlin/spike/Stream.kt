package spike

import com.sun.net.httpserver.HttpServer
import it.tdlight.jni.TdApi
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

// Servidor HTTP en 127.0.0.1 con Range sobre DownloadFile + ReadFilePart:
// cualquier reproductor (mpv, libVLC) hace seek pidiendo otro rango y TDLib
// salta la descarga a ese punto. Es la opción B de la spec 16 (punto 5).
class Stream(private val td: Tdl) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    var served = 0L
        private set

    fun url(fileId: Int) = "http://127.0.0.1:$port/f/$fileId"

    fun start() {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/f/") { ex ->
            try {
                val id = ex.requestURI.path.substringAfterLast('/').toInt()
                val f = runBlocking { td.send(TdApi.GetFile(id)) } as TdApi.File
                val size = f.size.takeIf { it > 0 } ?: f.expectedSize
                val range = ex.requestHeaders.getFirst("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                val end = range?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                ex.sendResponseHeaders(if (range != null) 206 else 200, end - start + 1)
                ex.responseBody.use { out ->
                    var pos = start
                    while (pos <= end) {
                        val want = minOf(CHUNK, end - pos + 1)
                        val bytes = runBlocking { read(id, pos, want) } ?: break
                        out.write(bytes)
                        pos += bytes.size
                        served += bytes.size
                    }
                }
            } catch (e: Exception) {
                // El reproductor corta la conexión al hacer seek: normal.
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    // Pide la descarga desde pos y espera a que haya bytes contiguos ahí.
    private suspend fun read(id: Int, pos: Long, want: Long): ByteArray? {
        td.send(TdApi.DownloadFile(id, 32, pos, 0, false))
        val have = withTimeoutOrNull(60_000) {
            var have: Long
            while (true) {
                have = (td.send(TdApi.GetFileDownloadedPrefixSize(id, pos)) as? TdApi.FileDownloadedPrefixSize)?.size ?: 0
                val done = td.file(id).value?.local?.isDownloadingCompleted == true
                if (have >= want || (done && have > 0)) break
                delay(100)
            }
            have
        } ?: return null
        val r = td.send(TdApi.ReadFilePart(id, pos, minOf(want, have)))
        return (r as? TdApi.Data)?.data?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val CHUNK = 512L * 1024
    }
}
