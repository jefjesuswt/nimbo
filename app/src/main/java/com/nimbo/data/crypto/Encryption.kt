package com.nimbo.data.crypto

import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.ok
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.keyText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// OFF = nunca configurado · LOCKED = hay clave en la nube, no en este
// teléfono · READY = se puede cifrar y abrir.
enum class EncState { OFF, LOCKED, READY }

enum class SetupResult { OK, EXISTS, OFFLINE, FAILED }

// Ciclo de la clave maestra (spec 12): crear, desbloquear, bloquear.
@Singleton
class Encryption @Inject constructor(
    private val client: TdClient,
    private val repo: CloudRepository,
    private val keys: KeyRing,
    private val prefs: AppPrefs,
    @ApplicationScope scope: CoroutineScope
) {
    val state: StateFlow<EncState> = combine(repo.keyText, keys.key) { t, k ->
        when {
            k != null -> EncState.READY
            t != null -> EncState.LOCKED
            else -> EncState.OFF
        }
    }.stateIn(scope, SharingStarted.Eagerly, EncState.OFF)

    // Maestra nueva envuelta con la contraseña, guardada como texto en
    // Saved Messages. Solo con el listado al día: dos claves dejarían
    // archivos sin abrir.
    suspend fun setup(password: CharArray): SetupResult = withContext(Dispatchers.Default) {
        repo.reload()
        if (!repo.synced) return@withContext SetupResult.OFFLINE
        if (repo.keyText.value != null) return@withContext SetupResult.EXISTS
        val master = randomBytes(32)
        val text = keyText(wrapMaster(password, master))
        val msg = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
        if (!client.ok(TdApi.SendMessage(repo.chat(), null, null, null, null, msg))) return@withContext SetupResult.FAILED
        keys.set(master)
        prefs.encryptUploads = true
        repo.refresh()
        SetupResult.OK
    }

    // false = contraseña incorrecta.
    suspend fun unlock(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val k = repo.keyText.value?.second ?: return@withContext false
        val master = unwrapMaster(password, k) ?: return@withContext false
        keys.set(master)
        true
    }

    // Olvida la clave en este teléfono (la nube la conserva).
    fun lock() = keys.set(null)
}
