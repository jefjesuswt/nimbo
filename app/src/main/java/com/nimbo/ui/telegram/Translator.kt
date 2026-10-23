package com.nimbo.ui.telegram

import android.content.Context

// Proveedor de traducción del lector. Solo el build personal trae
// proveedores (ML Kit en el teléfono + web de Google, ver FlavorTranslators);
// el público no traduce: sin lista, el lector no muestra traducir.
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

object Translators {
    private const val PREF = "translator"
    const val MODELS_MOBILE = "tr_models_mobile"

    val all: List<Translator> by lazy { flavorTranslators() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE)

    // null = build sin traducción (público).
    fun current(ctx: Context): Translator? =
        all.firstOrNull { it.id == prefs(ctx).getString(PREF, null) } ?: all.firstOrNull()

    fun set(ctx: Context, id: String) = prefs(ctx).edit().putString(PREF, id).apply()

    private val cache = mutableMapOf<String, String>()

    // Caché en memoria por proveedor + par + texto.
    suspend fun translate(ctx: Context, tr: Translator, text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String {
        val key = "${tr.id}:$src>$tgt@${text.hashCode()}"
        cache[key]?.let { return it }
        return tr.translate(ctx, text, src, tgt, onStatus).also { cache[key] = it }
    }
}
