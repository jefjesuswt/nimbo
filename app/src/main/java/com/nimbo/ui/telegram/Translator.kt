package com.nimbo.ui.telegram

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Proveedor de traducción del lector. En el teléfono (ML Kit) siempre; el
// web de Google solo en el build personal (ver FlavorTranslators).
interface Translator {
    val id: String
    val label: Int
    // false = el texto sale del teléfono.
    val onDevice: Boolean
    val langs: List<String> get() = Langs

    // onStatus = texto de estado (p. ej. "descargando modelo"); TrError = fallo legible.
    suspend fun translate(ctx: Context, text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String
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

object Translators {
    private const val PREF = "translator"
    const val MODELS_MOBILE = "tr_models_mobile"

    val all: List<Translator> by lazy { listOf<Translator>(MlKitTranslator) + flavorTranslators() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE)

    fun current(ctx: Context): Translator =
        all.firstOrNull { it.id == prefs(ctx).getString(PREF, null) } ?: all.first()

    fun set(ctx: Context, id: String) = prefs(ctx).edit().putString(PREF, id).apply()

    private val cache = mutableMapOf<String, String>()

    // Caché en memoria por proveedor + par + texto.
    suspend fun translate(ctx: Context, tr: Translator, text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String {
        val key = "${tr.id}:$src>$tgt@${text.hashCode()}"
        cache[key]?.let { return it }
        return tr.translate(ctx, text, src, tgt, onStatus).also { cache[key] = it }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

// En el teléfono: modelos (~30 MB por idioma) bajo demanda; por defecto
// solo con Wi-Fi. Detección "auto" con language-id.
object MlKitTranslator : Translator {
    override val id = "mlkit"
    override val label = R.string.tr_mlkit
    override val onDevice = true

    override suspend fun translate(ctx: Context, text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String {
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
            val mobileOk = ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE).getBoolean(Translators.MODELS_MOBILE, false)
            if (!mobileOk && !unmetered(ctx)) throw TrError(R.string.tr_need_wifi)
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

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
