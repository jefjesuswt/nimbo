package com.nimbo

import com.nimbo.R
import com.nimbo.ui.settings.darkThemeFor
import com.nimbo.ui.settings.seedsVisible
import com.nimbo.ui.settings.themeModeLabel
import org.junit.Test
import org.junit.Assert.assertEquals

class SettingsTest {
    @Test fun labels() {
        assertEquals(R.string.theme_light, themeModeLabel("light"))
        assertEquals(R.string.theme_dark, themeModeLabel("dark"))
        assertEquals(R.string.theme_system, themeModeLabel("system"))
        assertEquals(R.string.theme_system, themeModeLabel("??"))
    }

    @Test fun darkFor() {
        assertEquals(false, darkThemeFor("light", true))
        assertEquals(true, darkThemeFor("dark", false))
        assertEquals(true, darkThemeFor("system", true))
        assertEquals(false, darkThemeFor("system", false))
    }

    @Test fun seeds() {
        assertEquals(false, seedsVisible(true))
        assertEquals(true, seedsVisible(false))
    }
}
