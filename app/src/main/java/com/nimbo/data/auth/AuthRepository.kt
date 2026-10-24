package com.nimbo.data.auth

import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.tdlib.ApiKeys
import com.nimbo.core.tdlib.SecretStore
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keys de my.telegram.org + sesión TDLib. Las keys solo viven cifradas en
// este teléfono (SecretStore). Al cerrar sesión, CloudRepository y el
// player se vacían solos (observan `auth`).
@Singleton
class AuthRepository @Inject constructor(
    private val client: TdClient,
    private val secrets: SecretStore,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    val auth: StateFlow<TgAuthState> = client.auth
    val error: StateFlow<String?> = client.error

    // null = aún leyendo el almacén cifrado (fuera del hilo principal).
    private val _keys = MutableStateFlow<ApiKeys?>(null)
    val keys: StateFlow<ApiKeys?> = _keys
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        scope.launch {
            val k = withContext(io) { secrets.load() }
            _keys.value = k
            _loaded.value = true
            if (k != null) withContext(io) { client.start(k.id, k.hash) }
        }
        // TDLib cerrado (vuelve a IDLE) con keys aún guardadas: arranca otra
        // instancia para poder volver a entrar (sin esto quedaba colgado).
        scope.launch {
            var prev = TgAuthState.IDLE
            client.auth.collect { a ->
                val k = _keys.value
                if (a == TgAuthState.IDLE && prev != TgAuthState.IDLE && k != null) {
                    withContext(io) { client.start(k.id, k.hash) }
                }
                prev = a
            }
        }
    }

    fun saveAndConnect(keys: ApiKeys) {
        _keys.value = keys
        scope.launch(io) {
            secrets.save(keys)
            client.start(keys.id, keys.hash.trim())
        }
    }

    // "Olvidar y cerrar sesión".
    fun forget() {
        _keys.value = null
        client.logout()
        scope.launch(io) { secrets.clear() }
    }

    fun sendPhone(phone: String) = client.sendPhone(phone)
    fun sendCode(code: String) = client.sendCode(code)
    fun sendPassword(pw: String) = client.sendPassword(pw)
}
