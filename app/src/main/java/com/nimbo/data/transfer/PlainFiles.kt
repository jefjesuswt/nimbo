package com.nimbo.data.transfer

import android.content.Context
import android.util.Log
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.util.trimCache
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.encrypted
import com.nimbo.data.cloud.incomplete
import com.nimbo.data.cloud.isSplit
import com.nimbo.data.crypto.DecryptingOutputStream
import com.nimbo.data.crypto.KeyRing
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

// El archivo en claro para abrir o descargar: une partes (spec 13) y
// descifra (spec 12) al vuelo. Uno normal es el path de TDLib tal cual.
@Singleton
class PlainFiles @Inject constructor(
    @ApplicationContext ctx: Context,
    private val client: TdClient,
    private val fetcher: FileFetcher,
    private val keys: KeyRing,
    @ApplicationScope scope: CoroutineScope
) {
    private val dir = File(ctx.cacheDir, "plain")

    init {
        // Bloquear o cerrar sesión: fuera todo lo descifrado del teléfono.
        scope.launch { keys.key.collect { if (it == null) dir.deleteRecursively() } }
    }

    suspend fun path(f: TgFile, stallMs: Long = 180_000, report: (Float) -> Unit = {}): String? {
        if (!f.isSplit() && !f.encrypted()) return fetcher.awaitFile(f.fileId, stallMs, report)
        val out = File(dir, "${f.messageId}-${f.name.replace('/', '_').takeLast(100)}")
        if (out.exists()) return out.path
        dir.mkdirs()
        val tmp = File(dir, out.name + ".tmp")
        val ok = tmp.outputStream().use { write(f, it, stallMs, report) }
        if (!ok || !tmp.renameTo(out)) {
            tmp.delete()
            return null
        }
        trimCache(dir, 2L shl 30)
        return out.path
    }

    // Une + descifra en out. false = bloqueado, faltan partes, no bajó o los
    // bytes no son auténticos (lo escrito en out ya no vale).
    suspend fun write(f: TgFile, out: OutputStream, stallMs: Long = 180_000, report: (Float) -> Unit = {}): Boolean {
        if (f.incomplete() || f.locked) return false
        val key = if (f.encrypted()) keys.key.value ?: return false else null
        val ids = if (f.isSplit()) f.parts.map { it.fileId } else listOf(f.fileId)
        return try {
            // Cifrado de una pieza: el total se sabe al bajarlo.
            var sink: OutputStream? = when {
                key == null -> out
                f.isSplit() -> DecryptingOutputStream(key, f.parts.sumOf { it.size }, out)
                else -> null
            }
            ids.forEachIndexed { i, id ->
                val path = fetcher.awaitFile(id, stallMs) { p -> report((i + p) / ids.size) } ?: return false
                val file = File(path)
                val s = sink ?: DecryptingOutputStream(key!!, file.length(), out).also { sink = it }
                file.inputStream().use { it.copyTo(s) }
                // Partes: fuera de la caché de TDLib al copiarlas (si no, el doble).
                if (f.isSplit()) client.send(TdApi.DeleteFile(id))
            }
            if (key != null) sink?.close()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PLAIN-ERR ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "PlainFiles"
    }
}
