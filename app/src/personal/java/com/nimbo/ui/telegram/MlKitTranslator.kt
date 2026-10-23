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

// Solo build personal: ML Kit y su librería nativa (~16 MB) no van en el público.

// Etiqueta del picker → código ML Kit ("zh-CN" → "zh"); null = no soportado. Pura.
fun mlkitTag(tag: String): String? {
    val base = tag.substringBefore('-').lowercase()
    return base.takeIf { it in TranslateLanguage.getAllLanguages() }
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
