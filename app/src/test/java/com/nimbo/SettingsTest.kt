package com.nimbo

import com.nimbo.R
import com.nimbo.core.prefs.ThemeMode
import com.nimbo.feature.settings.seedsVisible
import com.nimbo.feature.settings.themeModeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun labels() {
        assertEquals(R.string.theme_light, themeModeLabel(ThemeMode.of("light")))
        assertEquals(R.string.theme_dark, themeModeLabel(ThemeMode.of("dark")))
        assertEquals(R.string.theme_system, themeModeLabel(ThemeMode.of("system")))
        // Valor desconocido en prefs viejas → sistema.
        assertEquals(R.string.theme_system, themeModeLabel(ThemeMode.of("??")))
    }

    @Test fun darkFor() {
        assertEquals(false, ThemeMode.LIGHT.isDark(true))
        assertEquals(true, ThemeMode.DARK.isDark(false))
        assertEquals(true, ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
    }

    @Test fun seeds() {
        assertEquals(false, seedsVisible(true))
        assertEquals(true, seedsVisible(false))
    }
}
