package com.nimbo.ui.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Traducción cloud vía endpoint web de Google (no oficial, sin key):
// el texto SALE del teléfono. Solo al pulsar traducir.
// ponytail: endpoint no documentado, puede romperse o bloquearse; si
// pasa, ML Kit on-device (modelo ~30MB por idioma) o Cloud Translation con key.
object TgCloudTranslate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = mutableMapOf<String, String>()

    // Curados para el picker.
    val Langs = listOf(
        "es", "en", "ja", "pt", "fr", "de", "it", "zh-CN", "ko",
        "ru", "ar", "hi", "nl", "pl", "tr", "uk", "ca"
    )

    // Traduce (trocea >4000 chars). Cache en memoria.
    fun translate(text: String, srcTag: String, tgtTag: String, done: (String?) -> Unit) {
        val key = "$srcTag>$tgtTag@${text.hashCode()}"
        cache[key]?.let { done(it); return }
        scope.launch {
            val out = runCatching {
                val sl = if (srcTag == "auto") "auto" else norm(srcTag)
                chunks(text).map { q ->
                    withContext(Dispatchers.IO) { one(q, sl, norm(tgtTag)) }
                }.joinToString("")
            }.onFailure { android.util.Log.w("TgTranslate", "FAIL ${it.message}") }.getOrNull()
            if (out != null) cache[key] = out
            else android.util.Log.w("TgTranslate", "NULL-OUT")
            done(out)
        }
    }

    // ML Kit usaba "zh"; el endpoint quiere "zh-CN".
    private fun norm(tag: String): String = when (tag) {
        "zh" -> "zh-CN"
        else -> tag
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun one(q: String, sl: String, tl: String): String {
        // POST con headers de navegador: el GET pelado da 400.
        val url = URL("https://translate.googleapis.com/translate_a/single")
        val body = "client=gtx&sl=${enc(sl)}&tl=${enc(tl)}&dt=t&q=${enc(q)}"
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Referer", "https://translate.google.com/")
            setRequestProperty("Origin", "https://translate.google.com")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body) }
            if (conn.responseCode != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(120) }.getOrNull()
                android.util.Log.w("TgTranslate", "HTTP ${conn.responseCode} $err")
                throw IllegalStateException("http ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // [[[ "trad","orig",... ]], ... ] → concatena primer elemento.
            val sb = StringBuilder()
            val outer = JSONArray(body).getJSONArray(0)
            for (i in 0 until outer.length()) {
                sb.append(outer.getJSONArray(i).optString(0))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun chunks(text: String, max: Int = 4000): List<String> {
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
}
