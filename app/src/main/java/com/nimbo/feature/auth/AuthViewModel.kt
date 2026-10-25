package com.nimbo.feature.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nimbo.core.tdlib.ApiKeys
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.tdlib.parseApiId
import com.nimbo.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Paso del login. Antes `view = -1..3` en la Composable.
sealed interface AuthStep {
    data object Loading : AuthStep
    data object Keys : AuthStep
    data object Phone : AuthStep
    data object Code : AuthStep
    data object Password : AuthStep
    data object Ready : AuthStep
}

// Pura, testeable. `editPhone` = "número equivocado" estando en WAIT_CODE.
fun authStepOf(loaded: Boolean, hasKeys: Boolean, auth: TgAuthState, editPhone: Boolean): AuthStep = when {
    !loaded -> AuthStep.Loading
    !hasKeys -> AuthStep.Keys
    auth == TgAuthState.READY -> AuthStep.Ready
    auth == TgAuthState.WAIT_CODE && !editPhone -> AuthStep.Code
    auth == TgAuthState.WAIT_PASSWORD -> AuthStep.Password
    auth == TgAuthState.IDLE -> AuthStep.Loading
    else -> AuthStep.Phone
}

@HiltViewModel
class AuthViewModel @Inject constructor(private val repo: AuthRepository) : ViewModel() {
    private val editPhone = MutableStateFlow(false)

    val step: StateFlow<AuthStep> = combine(repo.loaded, repo.keys, repo.auth, editPhone) { l, k, a, e ->
        authStepOf(l, k != null, a, e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthStep.Loading)

    val error: StateFlow<String?> = repo.error

    var phone by mutableStateOf("")
    var country by mutableStateOf(defaultCountry())
    var sentTo by mutableStateOf("")
        private set
    // Código o contraseña rechazados (TDLib manda el error).
    var codeError by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { repo.error.collect { if (it != null) codeError = true } }
    }

    // false = keys inválidas.
    fun saveKeys(apiId: String, apiHash: String): Boolean {
        val id = parseApiId(apiId) ?: return false
        if (apiHash.isBlank()) return false
        repo.saveAndConnect(ApiKeys(id, apiHash.trim()))
        return true
    }

    fun sendPhone() {
        codeError = false
        editPhone.value = false
        sentTo = fullPhone(country.dial, phone)
        repo.sendPhone(sentTo)
    }

    fun sendCode(code: String) {
        codeError = false
        repo.sendCode(code)
    }

    fun sendPassword(pw: String) = repo.sendPassword(pw)

    fun wrongNumber() {
        editPhone.value = true
    }
}
