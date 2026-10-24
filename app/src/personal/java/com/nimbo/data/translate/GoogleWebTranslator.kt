package com.nimbo.data.translate

import com.nimbo.R
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// Solo build "personal": endpoint web de Google (no oficial, sin key). El
// texto SALE del teléfono. No está en la versión pública (ToS + privacidad).
// ponytail: endpoint no documentado, puede romperse o bloquearse.
class GoogleWebTranslator @Inject constructor() : Translator {
    override val id = "google-web"
    override val label = R.string.tr_google_web
    override val onDevice = false

    override suspend fun translate(text: String, src: String, tgt: String, onStatus: (Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val sl = if (src == "auto") "auto" else src
            chunkText(text).joinToString("") { one(it, sl, tgt) }
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
            if (conn.responseCode != 200) throw IllegalStateException("http ${conn.responseCode}")
            val res = conn.inputStream.bufferedReader().use { it.readText() }
            // [[[ "trad","orig",... ]], ... ] → concatena primer elemento.
            val sb = StringBuilder()
            val outer = JSONArray(res).getJSONArray(0)
            for (i in 0 until outer.length()) sb.append(outer.getJSONArray(i).optString(0))
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}

// Solo en el flavor personal: se suma al set de proveedores.
@Module
@InstallIn(SingletonComponent::class)
interface PersonalTranslateModule {
    @Binds
    @IntoSet
    fun googleWeb(t: GoogleWebTranslator): Translator
}
