package com.nimbo.core.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.defaultSort
import com.nimbo.ui.theme.Seed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class Appearance(val theme: ThemeMode, val seed: Seed, val dynamic: Boolean)

// Dueño único del SharedPreferences "nimbo". Mismas claves que antes del
// refactor: nada que migrar.
@Singleton
class AppPrefs @Inject constructor(private val p: SharedPreferences) {
    private val _appearance = MutableStateFlow(
        Appearance(
            theme = ThemeMode.of(p.getString(THEME_MODE, null)),
            seed = runCatching { Seed.valueOf(p.getString(SEED, null).orEmpty()) }.getOrDefault(Seed.OCEAN),
            dynamic = p.getBoolean(DYNAMIC, true)
        )
    )
    val appearance: StateFlow<Appearance> = _appearance

    fun setTheme(m: ThemeMode) {
        _appearance.value = _appearance.value.copy(theme = m)
        p.edit { putString(THEME_MODE, m.key) }
    }

    fun setSeed(s: Seed) {
        _appearance.value = _appearance.value.copy(seed = s)
        p.edit { putString(SEED, s.name) }
    }

    fun setDynamic(on: Boolean) {
        _appearance.value = _appearance.value.copy(dynamic = on)
        p.edit { putBoolean(DYNAMIC, on) }
    }

    // Orden por pestaña (9d).
    fun sort(tab: CloudTab): SortKey = SortKey.parse(p.getString("sort_${tab.key}", null)) ?: defaultSort(tab)

    fun setSort(tab: CloudTab, k: SortKey) = p.edit { putString("sort_${tab.key}", k.encode()) }

    var favs: Set<String>
        get() = p.getStringSet(FAVS, emptySet()).orEmpty()
        set(v) = p.edit { putStringSet(FAVS, v) }

    var translatorId: String?
        get() = p.getString(TRANSLATOR, null)
        set(v) = p.edit { putString(TRANSLATOR, v) }

    // ML Kit puede bajar modelos (~30 MB) con datos móviles.
    var modelsOnMobile: Boolean
        get() = p.getBoolean(MODELS_MOBILE, false)
        set(v) = p.edit { putBoolean(MODELS_MOBILE, v) }

    private companion object {
        const val THEME_MODE = "theme_mode"
        const val SEED = "seed"
        const val DYNAMIC = "dynamic"
        const val FAVS = "tg_favs"
        const val TRANSLATOR = "translator"
        const val MODELS_MOBILE = "tr_models_mobile"
    }
}
