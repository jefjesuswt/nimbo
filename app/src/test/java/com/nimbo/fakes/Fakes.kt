package com.nimbo.fakes

import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.local.CloudStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

// TDLib de mentira: responde por tipo de función y apunta lo enviado.
class FakeTdClient : TdClient {
    override val auth = MutableStateFlow(TgAuthState.IDLE)
    override val error = MutableStateFlow<String?>(null)
    val sent = mutableListOf<TdApi.Function<*>>()
    val handlers = mutableMapOf<Class<*>, (TdApi.Function<*>) -> TdApi.Object>()
    private val files = ConcurrentHashMap<Int, MutableStateFlow<TdApi.File?>>()

    inline fun <reified F : TdApi.Function<*>> on(noinline h: (F) -> TdApi.Object) {
        @Suppress("UNCHECKED_CAST")
        handlers[F::class.java] = h as (TdApi.Function<*>) -> TdApi.Object
    }

    inline fun <reified F : TdApi.Function<*>> sentOf(): List<F> = sent.filterIsInstance<F>()

    override fun start(apiId: Int, apiHash: String) {}
    override fun sendPhone(phone: String) {}
    override fun sendCode(code: String) {}
    override fun sendPassword(password: String) {}
    override fun logout() {
        auth.value = TgAuthState.IDLE
    }

    override suspend fun send(fn: TdApi.Function<*>): TdApi.Object {
        sent += fn
        return handlers[fn.javaClass]?.invoke(fn) ?: TdApi.Ok()
    }

    override fun file(fileId: Int): StateFlow<TdApi.File?> = files.getOrPut(fileId) { MutableStateFlow(null) }

    fun emitFile(f: TdApi.File) {
        files.getOrPut(f.id) { MutableStateFlow(null) }.value = f
    }

    // Saved Messages con estos mensajes (una página y luego vacío).
    fun savedMessages(vararg msgs: TdApi.Message) {
        on<TdApi.GetMe> { TdApi.User().apply { id = 7 } }
        on<TdApi.CreatePrivateChat> { TdApi.Chat().apply { id = 70 } }
        on<TdApi.GetChatHistory> { f ->
            if (f.fromMessageId == 0L) TdApi.Messages(msgs.size, msgs.toList().toTypedArray()) else TdApi.Messages(0, emptyArray())
        }
    }
}

class MemoryStore : CloudStore {
    val rows = linkedMapOf<Long, TgFile>()
    val pos = mutableMapOf<Long, Long>()
    var cleared = 0
    override fun upsert(fs: List<TgFile>) = fs.forEach { rows[it.messageId] = it }
    override fun all() = rows.values.toList()
    override fun setCover(messageId: Long, hash: String) {
        rows[messageId]?.let { rows[messageId] = it.copy(coverHash = hash) }
    }
    override fun delete(messageId: Long) {
        rows.remove(messageId)
    }
    override fun hashes() = rows.values.filter { it.coverHash.isNotEmpty() }.associate { it.messageId to it.coverHash }
    override fun clear() {
        cleared++
        rows.clear()
        pos.clear()
    }
    override fun savePos(messageId: Long, ms: Long) {
        pos[messageId] = ms
    }
    override fun positions() = pos.filterValues { it > 0 }
    override fun pos(messageId: Long) = pos[messageId] ?: 0L
}

// Documento en Saved Messages con caption (como lo deja una subida).
fun docMessage(id: Long, name: String, caption: String = "", fileId: Int = id.toInt()): TdApi.Message {
    val f = TdApi.File()
    f.id = fileId
    f.size = 10
    f.local = TdApi.LocalFile()
    f.remote = TdApi.RemoteFile().also { it.id = "r$fileId" }
    val d = TdApi.Document()
    d.fileName = name
    d.mimeType = ""
    d.document = f
    val m = TdApi.Message()
    m.id = id
    m.date = 1_700_000_000
    m.content = TdApi.MessageDocument(d, TdApi.FormattedText(caption, emptyArray()))
    return m
}
