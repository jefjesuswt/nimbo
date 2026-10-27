package com.nimbo.data.cloud

import android.util.Log
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.captionText
import com.nimbo.core.tdlib.ok
import com.nimbo.data.crypto.EncInfo
import com.nimbo.data.crypto.KeyRing
import com.nimbo.data.crypto.sealInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi

// Gestión 7e: todo es retag del caption (cero bytes), salvo borrar. Cada
// operación corre en el scope de la app: si la pantalla se cierra a mitad
// de mover 40 archivos, el lote termina igual.
@Singleton
class CloudOps @Inject constructor(
    private val client: TdClient,
    private val repo: CloudRepository,
    private val keys: KeyRing,
    @ApplicationScope private val scope: CoroutineScope
) {
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    private suspend fun currentCaption(messageId: Long): String? =
        repo.message(messageId)?.content?.captionText()

    private suspend fun setCaption(messageId: Long, caption: String): Boolean {
        val res = client.send(
            TdApi.EditMessageCaption(repo.chat(), messageId, null, TdApi.FormattedText(caption, emptyArray()), false)
        )
        if (res is TdApi.Error) Log.w(TAG, "CAPTION-ERR $messageId: ${res.code} ${res.message}")
        return res !is TdApi.Error
    }

    // Rename = título display en #tgmeta (instantáneo; el archivo no se toca).
    // Cifrado: el título va dentro de #enc; se re-sella en todas las partes.
    suspend fun renameTitle(f: TgFile, title: String): Boolean = detached {
        val ok = runCatching {
            if (f.encrypted()) {
                val key = keys.key.value ?: return@runCatching false
                val info = sealInfo(key, EncInfo(f.name, f.sizeBytes, tagMeta("", title, f.artist, f.durSec)))
                return@runCatching f.messageIds().all { id ->
                    val cap = currentCaption(id) ?: return@runCatching false
                    setCaption(id, tagEnc(cap.replace(Regex("$TgEncTag \\S+"), "").trim(), info))
                }
            }
            val cap = currentCaption(f.messageId) ?: ""
            val (_, artist, dur) = parseMeta(cap)
            setCaption(f.messageId, tagMeta(tagFolder(cap, parseFolder(cap)), title, artist, dur))
        }.getOrDefault(false)
        if (ok) repo.refresh()
        ok
    }

    // Mover varios: retag de carpeta en serie (pausa anti-flood).
    // Devuelve cuántos salieron bien.
    suspend fun move(files: List<TgFile>, dest: String): Int = detached {
        val target = normalizePath(dest)
        var n = 0
        files.forEach { f ->
            val ok = runCatching {
                f.messageIds().all { id ->
                    val cap = currentCaption(id) ?: return@runCatching false
                    setCaption(id, tagFolder(cap, target)).also { delay(400) }
                }
            }.getOrDefault(false)
            if (ok) n++ else Log.w(TAG, "MOVE-SKIP ${f.messageId}")
        }
        if (n > 0) repo.refresh()
        n
    }

    // Id remoto persistente: del row o en vivo (el índice offline no lo guarda).
    private suspend fun remoteIdOf(f: TgFile, messageId: Long): String {
        if (f.remoteId.isNotEmpty() && messageId == f.messageId) return f.remoteId
        return when (val c = repo.message(messageId)?.content) {
            is TdApi.MessageAudio -> c.audio.audio.remote?.id.orEmpty()
            is TdApi.MessageDocument -> c.document.document.remote?.id.orEmpty()
            is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.remote?.id.orEmpty()
            is TdApi.MessageVideo -> c.video.video.remote?.id.orEmpty()
            else -> ""
        }
    }

    // Copiar: nuevo mensaje con el mismo archivo remoto. Cero bytes
    // viajados, instantáneo del lado servidor.
    suspend fun copy(files: List<TgFile>, dest: String): Int = detached {
        val target = normalizePath(dest)
        var n = 0
        files.forEach { f ->
            val ok = runCatching {
                // Partes: cada una como documento (conserva #part y grupo).
                f.messageIds().all { id ->
                    val rid = remoteIdOf(f, id)
                    if (rid.isEmpty()) return@runCatching false
                    val cap = currentCaption(id) ?: tagFolder("", f.folder)
                    val caption = TdApi.FormattedText(tagFolder(cap, target), emptyArray())
                    val remote = TdApi.InputFileRemote(rid)
                    val content = when {
                        f.isSplit() -> TdApi.InputMessageDocument(remote, null, true, caption)
                        f.kind == TgKind.AUDIO -> TdApi.InputMessageAudio(remote, null, 0, "", "", caption)
                        f.kind == TgKind.VIDEO -> TdApi.InputMessageVideo(remote, null, null, 0, IntArray(0), 0, 0, 0, false, caption, false, null, false)
                        f.kind == TgKind.IMAGE -> TdApi.InputMessagePhoto(remote, null, IntArray(0), 0, 0, caption, false, null, false)
                        else -> TdApi.InputMessageDocument(remote, null, true, caption)
                    }
                    client.ok(TdApi.SendMessage(repo.chat(), null, null, null, null, content)).also { delay(600) }
                }
            }.getOrDefault(false)
            if (ok) n++ else Log.w(TAG, "COPY-SKIP ${f.messageId}")
        }
        if (n > 0) repo.refresh()
        n
    }

    suspend fun delete(f: TgFile): Boolean = detached {
        runCatching { repo.deleteMessages(f.messageIds()) }.getOrDefault(false)
    }

    // Rename/move carpeta: reescribe tag (subpaths intactos) + refresh.
    suspend fun renameFolder(old: String, new: String): Boolean = detached {
        val target = normalizePath(new)
        fun dest(folder: String): String {
            val rest = if (old.isEmpty()) folder else if (folder == old) "" else folder.removePrefix("$old/")
            return if (target.isEmpty()) rest else if (rest.isEmpty()) target else "$target/$rest"
        }
        val ok = runCatching {
            repo.inDir(old).forEach { f ->
                f.messageIds().forEach { id ->
                    val cap = currentCaption(id) ?: return@forEach
                    if (!setCaption(id, tagFolder(cap, dest(f.folder)))) error("caption")
                }
            }
            // Covers linkeadas mudan también (si no, el art se pierde).
            // Retag de carpeta: conserva #tgcover/#tggen/#tgname.
            repo.coversUnder(old).forEach { (folder, msgId) ->
                val cap = currentCaption(msgId) ?: tagCoverLink(tagFolder("", dest(folder)))
                if (!setCaption(msgId, tagFolder(cap, dest(folder)))) error("cover")
            }
            // Marcadores de vacía: texto sin caption → borra y recrea.
            val marks = repo.keepsUnder(old)
            if (marks.isNotEmpty()) {
                val chat = repo.chat()
                client.send(TdApi.DeleteMessages(chat, marks.map { it.second }.toLongArray(), true))
                marks.map { it.first }.distinct().map(::dest).filter { it.isNotEmpty() }.forEach { d ->
                    if (!sendKeepMark(chat, d)) error("keep")
                }
            }
            true
        }.getOrDefault(false)
        if (ok) repo.refresh()
        ok
    }

    // Crear carpeta virtual: marcador de texto (navega, 0 bytes).
    suspend fun createFolder(path: String): Boolean = detached {
        val target = normalizePath(path)
        if (target.isEmpty()) return@detached false
        val ok = runCatching { sendKeepMark(repo.chat(), target) }.getOrDefault(false)
        if (ok) repo.refresh()
        ok
    }

    private suspend fun sendKeepMark(chat: Long, folder: String): Boolean {
        val mark = TdApi.InputMessageText(TdApi.FormattedText("$TgTag$folder $TgKeepTag", emptyArray()), null, true)
        return client.ok(TdApi.SendMessage(chat, null, null, null, null, mark))
    }

    suspend fun deleteFolder(dir: String): Boolean = detached {
        val ids = repo.inDir(dir).flatMap { it.messageIds() } +
            repo.coversUnder(dir).map { it.second } + repo.keepsUnder(dir).map { it.second }
        runCatching { repo.deleteMessages(ids) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "CloudOps"
    }
}
