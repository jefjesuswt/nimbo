package com.nimbo

import com.nimbo.ui.settings.darkThemeFor
import com.nimbo.ui.settings.seedsVisible
import com.nimbo.ui.settings.themeModeLabel
import org.junit.Test
import org.junit.Assert.assertEquals

class SettingsTest {
    @Test fun labels() {
        assertEquals("Light", themeModeLabel("light"))
        assertEquals("Dark", themeModeLabel("dark"))
        assertEquals("System default", themeModeLabel("system"))
        assertEquals("System default", themeModeLabel("??"))
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
