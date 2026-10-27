package com.nimbo.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nimbo.R
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.core.prefs.Appearance
import com.nimbo.core.prefs.ThemeMode
import com.nimbo.core.tdlib.ApiKeys
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.tdlib.parseApiId
import com.nimbo.data.auth.AuthRepository
import com.nimbo.data.crypto.EncState
import com.nimbo.data.crypto.Encryption
import com.nimbo.data.crypto.SetupResult
import com.nimbo.data.translate.Translator
import com.nimbo.data.translate.TranslatorRegistry
import com.nimbo.ui.theme.Seed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.SYSTEM -> R.string.theme_system
}

// Semillas solo cuando dynamic apagado: con dynamic el wallpaper manda.
fun seedsVisible(dynamic: Boolean): Boolean = !dynamic

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val translators: TranslatorRegistry,
    private val auth: AuthRepository,
    private val encryption: Encryption
) : ViewModel() {
    val appearance: StateFlow<Appearance> = prefs.appearance
    fun setTheme(m: ThemeMode) = prefs.setTheme(m)
    fun setSeed(s: Seed) = prefs.setSeed(s)
    fun setDynamic(on: Boolean) = prefs.setDynamic(on)

    // Traducción: proveedor (solo si hay más de uno, build personal) +
    // permitir bajar modelos de ML Kit con datos móviles.
    val translatorsAll: List<Translator> = translators.all
    var translator by mutableStateOf(translators.current())
        private set
    var modelsOnMobile by mutableStateOf(prefs.modelsOnMobile)
        private set

    fun setTranslator(id: String) {
        translators.set(id)
        translator = translators.current()
    }

    fun allowModelsOnMobile(on: Boolean) {
        modelsOnMobile = on
        prefs.modelsOnMobile = on
    }

    // Telegram: keys + estado de la sesión.
    val authState: StateFlow<TgAuthState> = auth.auth
    val keys: StateFlow<ApiKeys?> = auth.keys

    // false = keys inválidas.
    fun saveKeys(apiId: String, apiHash: String): Boolean {
        val id = parseApiId(apiId) ?: return false
        if (apiHash.isBlank()) return false
        auth.saveAndConnect(ApiKeys(id, apiHash.trim()))
        return true
    }

    fun forget() = auth.forget()

    // Cifrado (spec 12): configurar, desbloquear, bloquear, cifrar subidas.
    val encState: StateFlow<EncState> = encryption.state
    var encryptUploads by mutableStateOf(prefs.encryptUploads)
        private set
    var encDialog by mutableStateOf<EncDialog?>(null)
        private set
    var encBusy by mutableStateOf(false)
        private set
    var encError by mutableStateOf<Int?>(null)
        private set

    fun encryptUploads(on: Boolean) {
        encryptUploads = on
        prefs.encryptUploads = on
    }

    fun openEncDialog(d: EncDialog?) {
        encDialog = d
        encError = null
    }

    fun submitPassword(pw: String, repeat: String) {
        val d = encDialog ?: return
        if (d == EncDialog.SETUP) {
            encError = when {
                pw.length < MIN_PASSWORD -> R.string.enc_too_short
                pw != repeat -> R.string.enc_mismatch
                else -> null
            }
            if (encError != null) return
        }
        encBusy = true
        viewModelScope.launch {
            encError = if (d == EncDialog.SETUP) when (encryption.setup(pw.toCharArray())) {
                SetupResult.OK -> null
                SetupResult.EXISTS -> R.string.enc_exists
                SetupResult.OFFLINE -> R.string.enc_offline
                SetupResult.FAILED -> R.string.enc_failed
            } else if (encryption.unlock(pw.toCharArray())) null else R.string.enc_wrong
            encBusy = false
            if (encError == null) {
                encDialog = null
                encryptUploads = prefs.encryptUploads
            }
        }
    }

    fun lockEnc() = encryption.lock()

    private companion object {
        const val MIN_PASSWORD = 8
    }
}

enum class EncDialog { SETUP, UNLOCK }
