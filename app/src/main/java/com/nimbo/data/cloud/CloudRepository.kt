package com.nimbo.data.cloud

import android.util.Log
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.tdlib.call
import com.nimbo.core.tdlib.ok
import com.nimbo.core.tdlib.orNull
import com.nimbo.data.cloud.local.CloudStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// Listado de Saved Messages + índice. La nube manda; el índice acelera y
// sirve offline. Dueño de la lista en memoria: el resto (operaciones,
// transferencias) la lee de aquí y pide refresh.
@Singleton
class CloudRepository @Inject constructor(
    private val client: TdClient,
    private val index: CloudStore,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val _files = MutableStateFlow<List<TgFile>>(emptyList())
    val files: StateFlow<List<TgFile>> = _files
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    // Carpetas vacías (marcadores #tgkeep) que también navegan.
    private val _keeps = MutableStateFlow<Set<String>>(emptySet())
    val keeps: StateFlow<Set<String>> = _keeps

    // Sesión actual (se resetea al cerrar sesión).
    @Volatile private var savedChat: Long? = null
    @Volatile var myUserId: Long = 0L
        private set
    // Covers de carpeta (folder → messageId): la generada es invisible en
    // lista, pero vive en el chat — borrar/mover carpeta la incluye.
    @Volatile var coverMsgs: Map<String, Long> = emptyMap()
        private set
    // Marcadores de carpeta vacía (folder → messageId del texto #tgkeep).
    @Volatile var keepMsgs: Map<String, Long> = emptyMap()
        private set
    @Volatile private var lastOkMs = 0L
    private val loadingNow = AtomicBoolean(false)

    // Borrado con deshacer (9b).
    private val hidden: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private val pendingDel = ConcurrentHashMap<Long, Pair<Job, List<TgFile>>>()
    private val tokens = AtomicLong()

    init {
        // READY → carga. Salir de READY (logout, otra cuenta) → nada de la
        // sesión anterior sobrevive: memoria, chat cacheado e índice.
        scope.launch {
            var wasReady = false
            client.auth.collect { a ->
                if (a == TgAuthState.READY) {
                    wasReady = true
                    refresh()
                } else if (wasReady) {
                    wasReady = false
                    reset()
                }
            }
        }
    }

    private suspend fun reset() {
        pendingDel.values.forEach { it.first.cancel() }
        pendingDel.clear()
        hidden.clear()
        savedChat = null
        myUserId = 0L
        coverMsgs = emptyMap()
        keepMsgs = emptyMap()
        lastOkMs = 0L
        _files.value = emptyList()
        _keeps.value = emptySet()
        withContext(io) { runCatching { index.clear() } }
    }

    fun refresh() {
        scope.launch { load() }
    }

    // Deslizar para refrescar (9c): espera a que termine.
    suspend fun reload() = load()

    // Auto-refresh: al abrir + cada X min visible. Barato si ya está al día.
    fun refreshIfStale(maxAgeMin: Long = 5) {
        if (System.currentTimeMillis() - lastOkMs < maxAgeMin * 60 * 1000) return
        refresh()
    }

    suspend fun chat(): Long = savedChat ?: resolveSaved().also { savedChat = it }

    private suspend fun resolveSaved(): Long {
        val me = client.call<TdApi.User>(TdApi.GetMe()).orNull() ?: throw IllegalStateException("me")
        myUserId = me.id
        val chat = client.call<TdApi.Chat>(TdApi.CreatePrivateChat(me.id, false)).orNull()
            ?: throw IllegalStateException("chat")
        return chat.id
    }

    suspend fun message(messageId: Long): TdApi.Message? =
        client.call<TdApi.Message>(TdApi.GetMessage(chat(), messageId)).orNull()

    // Borra en Telegram y, si sale bien, en memoria + índice.
    suspend fun deleteMessages(ids: Collection<Long>): Boolean {
        if (ids.isEmpty()) return true
        val chat = chat()
        val ok = ids.distinct().chunked(100).all { client.ok(TdApi.DeleteMessages(chat, it.toLongArray(), true)) }
        if (ok) forget(ids)
        return ok
    }

    fun forget(ids: Collection<Long>) {
        val set = ids.toSet()
        _files.update { fs -> fs.filterNot { it.messageId in set } }
        scope.launch(io) { runCatching { set.forEach(index::delete) } }
    }

    // Se oculta ya y se borra en Telegram a los 5 s. Si la app muere antes,
    // no se borra (lado seguro). Devuelve el token para deshacer.
    fun deleteWithUndo(files: List<TgFile>): Long {
        val ids = files.map { it.messageId }.toSet()
        hidden += ids
        _files.update { fs -> fs.filterNot { it.messageId in ids } }
        val token = tokens.incrementAndGet()
        val job = scope.launch {
            delay(5_000)
            pendingDel.remove(token)
            val ok = runCatching { deleteMessages(ids) }.getOrDefault(false)
            hidden -= ids
            if (!ok) refresh()
        }
        pendingDel[token] = job to files
        return token
    }

    fun undoDelete(token: Long) {
        val (job, files) = pendingDel.remove(token) ?: return
        job.cancel()
        hidden -= files.map { it.messageId }.toSet()
        _files.update { fs -> (fs + files).sortedByDescending { it.messageId } }
    }

    // Cover deducida (hash): memoria + índice para no re-bajar.
    fun noteCover(messageId: Long, hash: String) {
        _files.update { fs -> fs.map { if (it.messageId == messageId) it.copy(coverHash = hash) else it } }
        scope.launch(io) { runCatching { index.setCover(messageId, hash) } }
    }

    // ponytail: opera sobre memoria (tope 20×100 del listado).
    fun inDir(dir: String): List<TgFile> {
        val all = _files.value
        if (dir.isEmpty()) return all.filter { it.folder.isEmpty() }
        val prefix = "$dir/"
        return all.filter { it.folder == dir || it.folder.startsWith(prefix) }
    }

    // Covers bajo dir (incluye subdirs): folder → msgId.
    fun coversUnder(dir: String): List<Pair<String, Long>> {
        val prefix = "$dir/"
        return coverMsgs.toList().filter { (folder, _) ->
            folder == dir || (dir.isNotEmpty() && folder.startsWith(prefix))
        }
    }

    // Marcadores de carpeta vacía bajo dir (incluye subdirs).
    fun keepsUnder(dir: String): List<Pair<String, Long>> {
        val prefix = "$dir/"
        return keepMsgs.toList().filter { (folder, _) ->
            folder == dir || (dir.isNotEmpty() && folder.startsWith(prefix)) || dir.isEmpty()
        }
    }

    private suspend fun load() {
        if (!loadingNow.compareAndSet(false, true)) return
        _loading.value = true
        try {
            val chat = chat()
            val all = mutableListOf<TgFile>()
            val links = mutableMapOf<String, Int>()
            val coverIds = mutableMapOf<String, Long>()
            val keeps = mutableMapOf<String, Long>()
            val seen = mutableSetOf<Long>()
            var from = 0L
            var pages = 0
            // Hasta página vacía (tope 20×100; scroll infinito si duele).
            while (pages < 20) {
                pages++
                val page = client.call<TdApi.Messages>(TdApi.GetChatHistory(chat, from, 0, 100, false))
                    .orNull()?.messages ?: emptyArray()
                if (page.isEmpty()) break
                var added = 0
                page.forEach { m ->
                    if (!seen.add(m.id)) return@forEach
                    added++
                    // Marcador de carpeta vacía: no es archivo, pero navega.
                    val text = (m.content as? TdApi.MessageText)?.text?.text.orEmpty()
                    if (isKeepMark(text)) {
                        val kf = parseFolder(text)
                        if (kf.isNotEmpty() && kf !in keeps) keeps[kf] = m.id
                        return@forEach
                    }
                    coverLinkOf(m)?.let { link ->
                        // Clave repetida (re-subida): manda la más nueva.
                        if (!links.containsKey(link.first)) {
                            links[link.first] = link.second
                            coverIds[link.first] = m.id
                        }
                    }
                    // La generada da null (oculta); la de archivo se lista.
                    mapMessage(m)?.let { all.add(it) }
                }
                val last = page.last().id
                if (added == 0 && last == from) break
                from = last
            }
            // Hashes ya deducidos (índice): el refresh no los borra.
            val known = withContext(io) { runCatching { index.hashes() }.getOrDefault(emptyMap()) }
            val withKnown = all.map { f ->
                if (f.coverHash.isEmpty()) known[f.messageId]?.let { f.copy(coverHash = it) } ?: f else f
            }
            val visible = applyCoverLinks(withKnown, links).sortedByDescending { it.messageId }
            _files.value = visible.filterNot { it.messageId in hidden }
            coverMsgs = coverIds
            keepMsgs = keeps
            _keeps.value = keeps.keys
            lastOkMs = System.currentTimeMillis()
            withContext(io) { runCatching { index.upsert(visible) } }
        } catch (e: Exception) {
            Log.w(TAG, "LOAD-ERR ${e.message}")
            // Offline: índice manda si hay filas.
            val cached = withContext(io) { runCatching { index.all() }.getOrDefault(emptyList()) }
            if (cached.isNotEmpty()) _files.value = cached
        } finally {
            _loading.value = false
            loadingNow.set(false)
        }
    }

    private companion object {
        const val TAG = "CloudRepo"
    }
}
