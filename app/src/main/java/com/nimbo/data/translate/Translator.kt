package com.nimbo.data.translate

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nimbo.R
import com.nimbo.core.prefs.AppPrefs
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// Proveedor de traducción del lector. En el teléfono (ML Kit) siempre; el
// web de Google solo en el build personal (se suma por multibinding).
interface Translator {
    val id: String
    val label: Int
    // false = el texto sale del teléfono.
    val onDevice: Boolean
    val langs: List<String> get() = Langs

    // onStatus = texto de estado (p. ej. "descargando modelo"); TrError = fallo legible.
    suspend fun translate(text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String
}

class TrError(val res: Int) : Exception()

// Curados para el picker (todos existen en ML Kit).
val Langs = listOf(
    "es", "en", "ja", "pt", "fr", "de", "it", "zh-CN", "ko",
    "ru", "ar", "hi", "nl", "pl", "tr", "uk", "ca"
)

// Trozos de hasta `max` chars cortando por párrafo, línea o espacio. Pura.
fun chunkText(text: String, max: Int = 4000): List<String> {
    if (text.length <= max) return listOf(text)
    val out = mutableListOf<String>()
    var rest = text
    while (rest.length > max) {
        var cut = rest.lastIndexOf("\n\n", max).takeIf { it > max / 2 }
            ?: rest.lastIndexOf('\n', max).takeIf { it > max / 2 }
            ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 }
            ?: max
        if (cut <= 0) cut = max
        out.add(rest.substring(0, cut))
        rest = rest.substring(cut)
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out
}

// Etiqueta del picker → código ML Kit ("zh-CN" → "zh"); null = no soportado. Pura.
fun mlkitTag(tag: String): String? {
    val base = tag.substringBefore('-').lowercase()
    return base.takeIf { it in TranslateLanguage.getAllLanguages() }
}

// Proveedores disponibles + el elegido en More. ML Kit siempre primero.
@Singleton
class TranslatorRegistry @Inject constructor(
    translators: Set<@JvmSuppressWildcards Translator>,
    private val prefs: AppPrefs
) {
    val all: List<Translator> = translators.sortedBy { if (it.onDevice) 0 else 1 }

    fun current(): Translator = all.firstOrNull { it.id == prefs.translatorId } ?: all.first()

    fun set(id: String) {
        prefs.translatorId = id
    }

    // ponytail: caché en memoria por proveedor + par + texto, sin tope (textos ≤ 512 KB, pocos por sesión).
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun translate(tr: Translator, text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String {
        val key = "${tr.id}:$src>$tgt@${text.hashCode()}"
        cache[key]?.let { return it }
        return tr.translate(text, src, tgt, onStatus).also { cache[key] = it }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface TranslateModule {
    @Binds
    @IntoSet
    fun mlkit(t: MlKitTranslator): Translator
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

// En el teléfono: modelos (~30 MB por idioma) bajo demanda; por defecto
// solo con Wi-Fi. Detección "auto" con language-id.
class MlKitTranslator @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: AppPrefs
) : Translator {
    override val id = "mlkit"
    override val label = R.string.tr_mlkit
    override val onDevice = true

    override suspend fun translate(text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String {
        val to = mlkitTag(tgt) ?: throw TrError(R.string.tr_unsupported)
        val from = if (src == "auto") {
            val id = LanguageIdentification.getClient().identifyLanguage(text.take(2000)).await()
            if (id == "und") throw TrError(R.string.tr_cant_detect)
            mlkitTag(id) ?: throw TrError(R.string.tr_unsupported)
        } else mlkitTag(src) ?: throw TrError(R.string.tr_unsupported)
        if (from == to) return text
        val models = RemoteModelManager.getInstance()
        val missing = listOf(from, to).filter { it != TranslateLanguage.ENGLISH }.any {
            !models.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
        if (missing) {
            val mobileOk = prefs.modelsOnMobile
            if (!mobileOk && !unmetered()) throw TrError(R.string.tr_need_wifi)
            onStatus(R.string.tr_downloading_model)
        }
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            onStatus(R.string.tr_working)
            // ponytail: trozos en serie; paralelo si un texto largo tarda demasiado.
            val out = StringBuilder()
            for (c in chunkText(text)) out.append(client.translate(c).await())
            return out.toString()
        } finally {
            client.close()
        }
    }

    private fun unmetered(): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
