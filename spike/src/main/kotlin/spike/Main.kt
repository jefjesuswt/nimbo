package spike

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import it.tdlight.jni.TdApi
import java.awt.image.BufferedImage

// Lo que ve el spike de cada mensaje de Saved Messages con #nimbo/.
data class Item(val fileId: Int, val name: String, val folder: String, val size: Long, val kind: String)

private fun folderOf(caption: String): String? = caption.split(Regex("\\s+"))
    .firstOrNull { it.startsWith("#nimbo/") || it.startsWith("#toolbox/") }
    ?.substringAfter('/')

private fun itemOf(m: TdApi.Message): Item? {
    val c = m.content
    val (cap, file, name, kind) = when (c) {
        is TdApi.MessageDocument -> listOf(c.caption.text, c.document.document, c.document.fileName, "doc")
        is TdApi.MessageAudio -> listOf(c.caption.text, c.audio.audio, c.audio.fileName, "audio")
        is TdApi.MessageVideo -> listOf(c.caption.text, c.video.video, c.video.fileName, "video")
        else -> return null
    }
    val folder = folderOf(cap as String) ?: return null
    val f = file as TdApi.File
    return Item(f.id, name as String, folder, f.size, kind as String)
}

suspend fun loadSaved(td: Tdl, out: MutableList<Item>, total: (Int) -> Unit) {
    val me = td.send(TdApi.GetMe()) as TdApi.User
    val chat = td.send(TdApi.CreatePrivateChat(me.id, false)) as TdApi.Chat
    var from = 0L
    var seen = 0
    while (true) {
        val r = td.send(TdApi.GetChatHistory(chat.id, from, 0, 100, false)) as? TdApi.Messages ?: break
        if (r.messages.isEmpty()) break
        seen += r.messages.size
        total(seen)
        r.messages.mapNotNullTo(out, ::itemOf)
        from = r.messages.last().id
    }
}

private fun qr(link: String): BufferedImage {
    val m = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320)
    return BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setRGB(x, y, if (m[x, y]) 0 else 0xFFFFFF)
    }
}

fun main(args: Array<String>) {
    println("tdlib " + Tdl.load())
    if ("--smoke" in args) {
        Dirs.natives.listFiles()?.forEach { println("native " + it.path) }
        System.exit(0)
    }
    val (id, hash) = readKeys()
    val td = Tdl(id, hash).also { it.start() }
    val stream = Stream(td).also { it.start() }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Nimbo spike 16.0") {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(td, stream) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App(td: Tdl, stream: Stream) {
    val auth by td.auth.collectAsState()
    val err by td.error.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        err?.let { Text("TDLib: $it", color = MaterialTheme.colorScheme.error) }
        when (val a = auth) {
            Auth.Loading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            Auth.WaitPhone -> {
                Button(onClick = td::requestQr) { Text("Entrar con QR") }
                Field("o teléfono (+1…)", false, td::sendPhone)
            }
            is Auth.WaitQr -> {
                Text("Telegram en el teléfono › Ajustes › Dispositivos › Vincular dispositivo, y escanea:")
                Image(remember(a.link) { qr(a.link).toComposeImageBitmap() }, null, Modifier.size(320.dp))
            }
            Auth.WaitCode -> Field("Código", false, td::sendCode)
            is Auth.WaitPassword -> Field("Contraseña 2FA" + if (a.hint.isNotEmpty()) " (pista: ${a.hint})" else "", true, td::sendPassword)
            Auth.Ready -> Library(td, stream)
            is Auth.Failed -> Text("Sesión cerrada: ${a.msg}")
        }
    }
}

@Composable
private fun Field(label: String, secret: Boolean, onSend: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            v, { v = it }, label = { Text(label) }, singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
        )
        Button(onClick = { onSend(v.trim()) }, enabled = v.isNotBlank()) { Text("Enviar") }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Library(td: Tdl, stream: Stream) {
    val items = remember { mutableStateListOf<Item>() }
    var seen by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t = System.currentTimeMillis()
        val all = mutableListOf<Item>()
        loadSaved(td, all) { seen = it }
        items.addAll(all.sortedWith(compareBy({ it.folder }, { it.name })))
        println("listed ${items.size} of $seen messages in ${System.currentTimeMillis() - t} ms")
        done = true
    }
    Text("Mensajes leídos: $seen · con #nimbo/: ${items.size}" + if (done) "" else " …")
    if (!done) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn {
        items(items, key = { it.fileId }) { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(f.name)
                    Text("${f.folder} · ${f.kind} · ${f.size / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                }
                if (f.kind != "doc" || f.name.substringAfterLast('.').lowercase() in MEDIA) {
                    TextButton(onClick = { play(stream, f) }) { Text("mpv") }
                }
            }
        }
    }
}

private val MEDIA = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "mp4", "mkv", "webm", "mov", "avi")

// ponytail: el spike reproduce con mpv externo por la URL local; embebido
// (libmpv/vlcj) se evalúa aparte (decisión 7).
private fun play(stream: Stream, f: Item) {
    println("play ${f.name} ${stream.url(f.fileId)}")
    ProcessBuilder("mpv", "--force-window=yes", "--title=${f.name}", stream.url(f.fileId)).inheritIO().start()
}
