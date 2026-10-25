package com.nimbo.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

// Texto que decide un ViewModel y resuelve la UI (el VM no toca Resources).
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Res -> res.getString(id, *args.toTypedArray())
        is Plural -> res.getQuantityString(id, count, *args.toTypedArray())
        is Raw -> text
    }
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

// Snackbar de un evento: texto + acción opcional (p. ej. "Deshacer").
data class Snack(val text: UiText, val action: UiText? = null, val onAction: (() -> Unit)? = null)
