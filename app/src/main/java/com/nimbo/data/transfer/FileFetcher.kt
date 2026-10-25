package com.nimbo.data.transfer

import android.util.Log
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.awaitFile
import com.nimbo.core.tdlib.localPathOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// Descarga completa compartida (player, visor, lector, subtítulos): pide el
// archivo, reporta 0..1 y devuelve el path local o null.
@Singleton
class FileFetcher @Inject constructor(private val client: TdClient) {
    // Descargas completas en vuelo: las covers por rango no deben pedir el
    // mismo archivo (TDLib cancela una con la otra).
    private val fullActive: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun isFullActive(id: Int): Boolean = id in fullActive

    // stallMs = tiempo máximo SIN progreso (no duración total: un audio
    // grande con mala red puede tardar mucho y seguir avanzando).
    suspend fun awaitFile(fileId: Int, stallMs: Long = 180_000, report: (Float) -> Unit = {}): String? {
        if (fileId == 0) return null
        val res = client.send(TdApi.DownloadFile(fileId, 32, 0, 0, false))
        if (res is TdApi.Error) Log.w(TAG, "dl $fileId ERR ${res.code} ${res.message}")
        (res as? TdApi.File)?.let { if (it.local.isDownloadingCompleted) return it.localPathOrNull() }
        report(0f)
        fullActive.add(fileId)
        try {
            var last: TdApi.File? = null
            while (true) {
                val f = withTimeoutOrNull(stallMs) {
                    client.file(fileId).filterNotNull().first { it !== last }
                } ?: return null
                if (f.local.isDownloadingCompleted) return f.localPathOrNull()
                val tot = f.size.takeIf { it > 0 } ?: f.expectedSize.takeIf { it > 0 } ?: 0L
                val got = maxOf(f.local.downloadedSize, f.local.downloadedPrefixSize)
                if (tot > 0) report((got.toFloat() / tot).coerceIn(0f, 1f))
                last = f
            }
        } finally {
            fullActive.remove(fileId)
        }
    }

    // Descarga síncrona (TDLib responde al terminar): thumbs, visor sistema.
    suspend fun download(fileId: Int): String? {
        if (fileId == 0) return null
        val f = client.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) as? TdApi.File ?: return null
        return f.localPathOrNull()
    }

    // Prefijo (cabecera) del archivo: covers embebidas sin bajar el audio.
    suspend fun head(fileId: Int, bytes: Long, enough: Long, timeoutMs: Long): String? {
        client.send(TdApi.DownloadFile(fileId, 32, 0, bytes, true))
        return client.awaitFile(fileId, timeoutMs) {
            it.local.isDownloadingCompleted || it.local.downloadedPrefixSize >= enough
        }?.localPathOrNull()
    }

    private companion object {
        const val TAG = "FileFetcher"
    }
}
