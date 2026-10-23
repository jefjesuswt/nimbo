package com.nimbo.ui.telegram

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Uri compartida desde fuera (ACTION_SEND). La consume TelegramRoute
// cuando hay sesión; antes no (subir exige login).
object SharedUpload {
    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri

    fun put(u: Uri) {
        _uri.value = u
    }

    fun take(): Uri? {
        val u = _uri.value
        _uri.value = null
        return u
    }
}
