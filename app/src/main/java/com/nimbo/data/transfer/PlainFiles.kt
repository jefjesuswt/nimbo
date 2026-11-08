package com.nimbo.data.transfer

import android.content.Context
import android.util.Log
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.util.trimCache
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.encrypted
import com.nimbo.data.cloud.fileIds
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
    @ApplicationScope private val scope: CoroutineScope
) {
    private val dir = File(ctx.cacheDir, "plain")

    init {
        // Bloquear o cerrar sesión: fuera todo lo descifrado del teléfono.
        scope.launch { keys.key.collect { if (it == null) dir.deleteRecursively() } }
        // Al entrar: lo que quedó de la sesión anterior (app matada, visor
        // del sistema) no se queda en el teléfono.
        scope.launch { client.auth.collect { if (it == TgAuthState.READY) sweep() } }
    }

    // Nube-first: nada de lo visto/escuchado se queda. Media fuera entera;
    // fotos y miniaturas (la grilla) con un tope chico, las más viejas fuera.
    suspend fun sweep() {
        client.send(TdApi.OptimizeStorage(0, 0, 0, 0, mediaTypes(), LongArray(0), LongArray(0), false, 0))
        client.send(TdApi.OptimizeStorage(THUMBS_CAP, -1, -1, -1, thumbTypes(), LongArray(0), LongArray(0), false, 0))
    }

    // Al cerrar el visor/lector/player: fuera la copia de TDLib (o la unida/
    // descifrada). Volver a abrirlo lo baja de nuevo.
    fun release(files: Collection<TgFile>) {
        if (files.isEmpty()) return
        val list = files.toList()
        scope.launch {
            list.forEach { f ->
                f.fileIds().forEach { client.send(TdApi.DeleteFile(it)) }
                if (f.isSplit() || f.encrypted()) out(f).delete()
            }
        }
    }

    private fun out(f: TgFile) = File(dir, "${f.messageId}-${f.name.replace('/', '_').takeLast(100)}")

    suspend fun path(f: TgFile, stallMs: Long = 180_000, report: (Float) -> Unit = {}): String? {
        if (!f.isSplit() && !f.encrypted()) return fetcher.awaitFile(f.fileId, stallMs, report)
        val out = out(f)
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
                // Ya copiado: fuera de la caché de TDLib (si no, el doble y se queda).
                client.send(TdApi.DeleteFile(id))
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
        const val THUMBS_CAP = 64L shl 20

        fun mediaTypes(): Array<TdApi.FileType> = arrayOf(
            TdApi.FileTypeVideo(), TdApi.FileTypeAudio(), TdApi.FileTypeDocument(), TdApi.FileTypeAnimation(),
            TdApi.FileTypeVoiceNote(), TdApi.FileTypeVideoNote(), TdApi.FileTypeTemp()
        )

        fun thumbTypes(): Array<TdApi.FileType> = arrayOf(TdApi.FileTypePhoto(), TdApi.FileTypeThumbnail())
    }
}
