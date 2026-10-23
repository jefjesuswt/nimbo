package com.nimbo.ui.settings

import android.content.Context
import androidx.core.content.edit

// Prefs con SharedPreferences (sin DataStore: 0 deps nuevas).
// Claves espejan renderSettings() de index.html (S.prefs + Theme.state).
object SettingsPrefs {
    const val FILE = "nimbo"
    const val THEME_MODE = "theme_mode" // system|light|dark
    const val SEED = "seed" // OCEAN|MEADOW|DUSK
    const val DYNAMIC = "dynamic"

    fun load(ctx: Context): Map<String, Any> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return mapOf(
            THEME_MODE to (p.getString(THEME_MODE, "system") ?: "system"),
            SEED to (p.getString(SEED, "OCEAN") ?: "OCEAN"),
            DYNAMIC to p.getBoolean(DYNAMIC, true)
        )
    }

    fun save(ctx: Context, key: String, value: Any) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }
    }
}

fun themeModeLabel(mode: String): String = when (mode) {
    "light" -> "Light"
    "dark" -> "Dark"
    else -> "System default"
}

fun darkThemeFor(mode: String, systemDark: Boolean): Boolean =
    when (mode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

// Semillas solo cuando dynamic apagado: con dynamic el wallpaper manda.
fun seedsVisible(dynamic: Boolean): Boolean = !dynamic
