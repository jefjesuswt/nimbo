package spike

import it.tdlight.ClientFactory
import it.tdlight.ExceptionHandler
import it.tdlight.ResultHandler
import it.tdlight.Init
import it.tdlight.TelegramClient
import it.tdlight.jni.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

// Directorios propios ($XDG_DATA_HOME/nimbo-spike), nunca /tmp: ahí extrae
// TDlight su nativo y vive la base de TDLib.
object Dirs {
    val data = File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "nimbo-spike")
    val natives = File(data, "natives")
    val tdlib = File(data, "tdlib")
}

// Claves de nimbo-cli (~/.config/nimbo/config): mismas que la app.
fun readKeys(): Pair<Int, String> {
    val kv = File(System.getProperty("user.home"), ".config/nimbo/config").readLines()
        .filter { '=' in it && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    return kv.getValue("api_id").toInt() to kv.getValue("api_hash")
}

sealed interface Auth {
    data object Loading : Auth
    data object WaitPhone : Auth
    data class WaitQr(val link: String) : Auth
    data object WaitCode : Auth
    data class WaitPassword(val hint: String) : Auth
    data object Ready : Auth
    data class Failed(val msg: String) : Auth
}

// Cliente mínimo: lo que hará TdlightClient en 16.5, sin la interfaz de 16.2.
class Tdl(private val apiId: Int, private val apiHash: String) {
    val auth = MutableStateFlow<Auth>(Auth.Loading)
    val error = MutableStateFlow<String?>(null)
    private val files = ConcurrentHashMap<Int, MutableStateFlow<TdApi.File?>>()
    private lateinit var client: TelegramClient

    fun file(id: Int): StateFlow<TdApi.File?> = files.getOrPut(id) { MutableStateFlow(null) }

    fun start() {
        client = ClientFactory.acquireCommonClientFactory().createClient()
        client.initialize(
            ResultHandler<TdApi.Update> { u -> onUpdate(u) },
            ExceptionHandler { e -> error.value = e.message },
            ExceptionHandler { e -> error.value = e.message }
        )
    }

    private fun onUpdate(u: TdApi.Object) {
        when (u) {
            is TdApi.UpdateFile -> files.getOrPut(u.file.id) { MutableStateFlow(null) }.value = u.file
            is TdApi.UpdateAuthorizationState -> onAuth(u.authorizationState)
            else -> {}
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        auth.value = when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                Dirs.tdlib.mkdirs()
                // Mismos 14 argumentos y orden que TdlibClient.kt de Android.
                // ponytail: spike sin clave de base (16.5: Secret Service).
                fire(
                    TdApi.SetTdlibParameters(
                        false, Dirs.tdlib.path, Dirs.tdlib.path, ByteArray(0),
                        true, true, true, false,
                        apiId, apiHash,
                        "en", "Nimbo Desktop (spike)", System.getProperty("os.name") + " " + System.getProperty("os.version"), "0.0.1"
                    )
                )
                Auth.Loading
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> Auth.WaitPhone
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> Auth.WaitQr(s.link)
            is TdApi.AuthorizationStateWaitCode -> Auth.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> Auth.WaitPassword(s.passwordHint)
            is TdApi.AuthorizationStateReady -> Auth.Ready
            is TdApi.AuthorizationStateClosed -> Auth.Failed("closed")
            else -> auth.value
        }
    }

    private fun fire(fn: TdApi.Function<*>) = client.send(fn) { r -> if (r is TdApi.Error) error.value = r.message }

    fun requestQr() = fire(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    fun sendPhone(p: String) = fire(TdApi.SetAuthenticationPhoneNumber(p, null))
    fun sendCode(c: String) = fire(TdApi.CheckAuthenticationCode(c))
    fun sendPassword(p: String) = fire(TdApi.CheckAuthenticationPassword(p))

    suspend fun send(fn: TdApi.Function<*>): TdApi.Object = suspendCancellableCoroutine { c ->
        client.send(fn, { r -> c.resume(r) }, { e -> c.resume(TdApi.Error(0, e.message ?: "tdlight")) })
    }.also { if (it is TdApi.File) files.getOrPut(it.id) { MutableStateFlow(null) }.value = it }

    companion object {
        // Carga el nativo con el workdir propio y devuelve la versión de TDLib.
        fun load(): String {
            Dirs.natives.mkdirs()
            System.setProperty("it.tdlight.native.workdir", Dirs.natives.absolutePath)
            // Por defecto lo borra tras cargarlo: se deja para ldd y para no reextraer.
            System.setProperty("it.tdlight.native.deleteLibAfterLoading", "false")
            Init.init()
            val c = ClientFactory.acquireCommonClientFactory().createClient()
            // execute exige initialize antes (si no, 503).
            c.initialize(ResultHandler<TdApi.Update> { }, ExceptionHandler { }, ExceptionHandler { })
            val r = c.execute(TdApi.GetOption("version"))
            c.send(TdApi.Close()) { }
            return (r as? TdApi.OptionValueString)?.value ?: r.toString()
        }
    }
}
