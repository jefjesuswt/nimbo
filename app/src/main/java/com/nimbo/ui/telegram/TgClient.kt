package com.nimbo.ui.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

// Estado auth TDLib. La UI solo observa; TDLib manda vía updates.
enum class TgAuthState { IDLE, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY }

// Singleton: TDLib prohíbe 2 instancias sobre mismo databaseDirectory.
object TgClient {
    private val _auth = MutableStateFlow(TgAuthState.IDLE)
    val auth: StateFlow<TgAuthState> = _auth

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Updates crudos para repo (mensajes nuevos, archivos, etc.).
    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
    val updates: SharedFlow<TdApi.Object> = _updates

    @Volatile private var client: Client? = null
    @Volatile private var started = false

    @Synchronized
    fun start(ctx: Context, apiId: Int, apiHash: String) {
        if (started) return
        started = true
        val dir = java.io.File(ctx.applicationContext.filesDir, "tdlib").apply { mkdirs() }
        val key = TgSecrets.dbKey(ctx)
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
        } catch (_: Exception) {
        }
        client = Client.create(
            { update -> onUpdate(apiId, apiHash, dir.absolutePath, key, update) },
            { _error.value = "update failed" },
            { _error.value = it.message }
        )
    }

    private fun onUpdate(apiId: Int, apiHash: String, dbDir: String, dbKey: ByteArray, update: TdApi.Object) {
        _updates.tryEmit(update)
        val state = (update as? TdApi.UpdateAuthorizationState)?.authorizationState ?: return
        when (state) {
            // Base cifrada: copiar files/tdlib no basta para robar la sesión.
            is TdApi.AuthorizationStateWaitTdlibParameters -> client?.send(
                TdApi.SetTdlibParameters(
                    false, dbDir, dbDir, dbKey,
                    true, true, true, false,
                    apiId, apiHash,
                    "en", "Android", "SDK ${Build.VERSION.SDK_INT}", "1.0"
                )
            ) { fail(it) }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TgAuthState.WAIT_PHONE
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TgAuthState.WAIT_CODE
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TgAuthState.WAIT_PASSWORD
            is TdApi.AuthorizationStateReady -> {
                _error.value = null
                _auth.value = TgAuthState.READY
            }
            // Logout/cierre: suelta instancia para poder re-loguear.
            is TdApi.AuthorizationStateClosed -> {
                client = null
                started = false
                _auth.value = TgAuthState.IDLE
            }
            else -> {}
        }
    }

    fun sendPhone(phone: String) {
        _error.value = null
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            false, false, false, false, false, null, null
        )
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, settings)) { fail(it) }
    }

    fun sendCode(code: String) {
        _error.value = null
        client?.send(TdApi.CheckAuthenticationCode(code)) { fail(it) }
    }

    fun sendPassword(password: String) {
        _error.value = null
        client?.send(TdApi.CheckAuthenticationPassword(password)) { fail(it) }
    }

    fun logout() {
        client?.send(TdApi.LogOut()) { fail(it) }
    }

    // Llamada suspendida: reanuda con respuesta o Error. Sin cliente → Error.
    suspend fun sendAwait(fn: TdApi.Function<*>): TdApi.Object =
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resume(TdApi.Error(0, "not started")) {}
                return@suspendCancellableCoroutine
            }
            try {
                c.send(fn, { res -> cont.resume(res) {} }, { e ->
                    cont.resume(TdApi.Error(0, e.message ?: "tdlib")) {}
                })
            } catch (e: Exception) {
                cont.resume(TdApi.Error(0, e.message ?: "send")) {}
            }
        }

    private fun fail(obj: TdApi.Object) {
        if (obj is TdApi.Error) _error.value = obj.message
    }
}
