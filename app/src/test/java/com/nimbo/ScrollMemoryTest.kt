package com.nimbo

import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.SortBy
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.feature.cloud.Browse
import com.nimbo.feature.cloud.buildListing
import com.nimbo.feature.cloud.scrollKeyOf
import com.nimbo.ui.common.ScrollMemory
import com.nimbo.ui.common.isAncestor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollMemoryTest {
    private val byName = SortKey(SortBy.NAME, true)

    @Test fun remembersUntilForgotten() {
        val m = ScrollMemory()
        assertEquals(0 to 0, m["d:"])
        m.save("d:", 42, 17)
        assertEquals(42 to 17, m["d:"])
        m.forget("d:")
        assertEquals(0 to 0, m["d:"])
    }

    // Miga o atrás a un padre = volver; a un hijo, hermano o el mismo = entrar.
    @Test fun ancestors() {
        assertTrue(isAncestor("", "music"))
        assertTrue(isAncestor("music", "music/rock/80s"))
        assertFalse(isAncestor("music", "music"))
        assertFalse(isAncestor("music/rock", "music"))
        assertFalse(isAncestor("mus", "music/rock"))
        assertFalse(isAncestor("", ""))
    }

    @Test fun keyPerScreen() {
        assertEquals("d:", scrollKeyOf(Browse(sort = byName)))
        assertEquals("d:music/rock", scrollKeyOf(Browse(dir = "music/rock", sort = byName)))
        assertEquals("a:pics/trip", scrollKeyOf(Browse(album = "pics/trip", sort = byName)))
        assertEquals("albums", scrollKeyOf(Browse(sort = byName), albums = true))
        // Buscando, la clave cambia con la consulta (vuelve arriba en cada una).
        assertNotEquals(scrollKeyOf(Browse(query = "a", sort = byName)), scrollKeyOf(Browse(query = "ab", sort = byName)))
        assertTrue(scrollKeyOf(Browse(dir = "music", query = "rock", sort = byName)).startsWith("?"))
    }

    // El Listing lleva la clave de su propio Browse: nunca la de otra carpeta.
    @Test fun listingCarriesItsKey() {
        val all = listOf(
            TgFile("1", "a.mp3", TgKind.AUDIO, "", 1, messageId = 1, folder = "music"),
            TgFile("2", "b.jpg", TgKind.IMAGE, "", 1, messageId = 2, folder = "pics")
        )
        assertEquals("d:music", buildListing(CloudTab.FILES, all, emptySet(), Browse(dir = "music", sort = byName)).scrollKey)
        assertEquals("albums", buildListing(CloudTab.AUDIO, all, emptySet(), Browse(sort = byName)).scrollKey)
        assertEquals("a:music", buildListing(CloudTab.AUDIO, all, emptySet(), Browse(album = "music", sort = byName)).scrollKey)
    }
}
