package com.nimbo.feature.reader

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nimbo.R
import com.nimbo.core.di.IoDispatcher
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.decodeText
import com.nimbo.data.cloud.siblingName
import com.nimbo.data.transfer.PlainFiles
import com.nimbo.data.transfer.TransferManager
import com.nimbo.data.translate.TrError
import com.nimbo.data.translate.Translator
import com.nimbo.data.translate.TranslatorRegistry
import com.nimbo.feature.main.ReaderRoute
import com.nimbo.ui.common.UiText
import com.nimbo.ui.common.text
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderPage { ORIGINAL, TRANSLATED }

// Lector multi-formato con encoding detectado + traducción por el
// proveedor elegido en More. Un hermano guardado ("05_x.es.txt") se abre
// sin re-traducir.
@HiltViewModel
class ReaderViewModel @Inject constructor(
    handle: SavedStateHandle,
    repo: CloudRepository,
    private val plain: PlainFiles,
    private val transfers: TransferManager,
    private val translators: TranslatorRegistry,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val messageId = handle.toRoute<ReaderRoute>().messageId

    val file: StateFlow<TgFile?> = repo.files.map { fs -> fs.firstOrNull { it.messageId == messageId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.files.value.firstOrNull { it.messageId == messageId })

    val translator: Translator = translators.current()

    var text by mutableStateOf<String?>(null)
        private set
    var encoding by mutableStateOf("")
        private set
    var tooBig by mutableStateOf(false)
        private set
    var page by mutableStateOf(ReaderPage.ORIGINAL)
        private set
    var translated by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    // Estado visible mientras traduce ("descargando modelo…").
    @get:StringRes
    var status by mutableStateOf<Int?>(null)
        private set
    var src by mutableStateOf("auto")
        private set
    var tgt by mutableStateOf(systemLang(translator.langs))
        private set
    private val tgtFlow = MutableStateFlow(tgt)

    // Hermano traducido ya guardado en la nube para el idioma destino.
    val sibling: StateFlow<TgFile?> = combine(repo.files, file.filterNotNull(), tgtFlow) { fs, f, t ->
        fs.firstOrNull { it.folder == f.folder && it.name == siblingName(f.name, t) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            file.filterNotNull().distinctUntilChanged { a, b -> a.messageId == b.messageId }.collect { load(it) }
        }
        viewModelScope.launch {
            sibling.filterNotNull().distinctUntilChanged { a, b -> a.messageId == b.messageId }.collect { s ->
                if (translated != null) return@collect
                val got = readText(s)?.first ?: return@collect
                translated = got
                page = ReaderPage.TRANSLATED
            }
        }
    }

    private suspend fun readText(f: TgFile): Pair<String, String>? = withContext(io) {
        val path = plain.path(f, 60_000) ?: return@withContext null
        runCatching {
            val bytes = java.io.File(path).inputStream().use { it.readBytes() }.take(512 * 1024).toByteArray()
            decodeText(bytes)
        }.getOrNull()
    }

    private suspend fun load(f: TgFile) {
        if (f.sizeBytes > 2L * 1024 * 1024) {
            tooBig = true
            return
        }
        val res = readText(f)
        if (res == null) _messages.send(text(R.string.could_not_open_text))
        else {
            text = res.first
            encoding = res.second
        }
    }

    fun translate(s: String, t: String) {
        src = s
        tgt = t
        tgtFlow.value = t
        val orig = text ?: return
        if (orig.isBlank()) {
            _messages.trySend(text(R.string.nothing_to_translate))
            return
        }
        working = true
        status = null
        translated = null
        page = ReaderPage.TRANSLATED
        viewModelScope.launch {
            val out = runCatching { translators.translate(translator, orig, s, t) { status = it } }
            working = false
            out.onSuccess { translated = it }.onFailure {
                _messages.send(text((it as? TrError)?.res ?: R.string.translation_failed))
                page = ReaderPage.ORIGINAL
            }
        }
    }

    fun show(p: ReaderPage) {
        if (p == ReaderPage.TRANSLATED && translated == null) translate(src, tgt)
        else page = p
    }

    fun save() {
        val f = file.value ?: return
        val cur = translated ?: return
        val t = tgt
        transfers.saveTranslation(f, t, cur) { ok ->
            _messages.trySend(if (ok) text(R.string.saved_name, siblingName(f.name, t)) else text(R.string.save_failed))
        }
    }

    fun download() {
        val f = file.value ?: return
        viewModelScope.launch {
            _messages.send(text(if (transfers.download(f)) R.string.saved_to_downloads else R.string.download_failed))
        }
    }
}

private fun systemLang(langs: List<String>): String {
    val tag = Locale.getDefault().language
    return if (tag in langs) tag else "es"
}
