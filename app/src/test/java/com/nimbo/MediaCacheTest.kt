package com.nimbo

import com.nimbo.core.prefs.CacheLimit
import com.nimbo.core.prefs.KeepMedia
import com.nimbo.data.transfer.autoCleanRequest
import com.nimbo.data.transfer.clearRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCacheTest {
    @Test fun autoCleanUsesDaysAndLimit() {
        val r = autoCleanRequest(KeepMedia.WEEK, CacheLimit.GB5)
        assertEquals(7 * 86_400, r.ttl)
        assertEquals(5L shl 30, r.size)
        // Lo abierto en la última hora no se toca.
        assertEquals(3_600, r.immunityDelay)
        // Vacío = todos los tipos.
        assertEquals(0, r.fileTypes.size)
    }

    // "Siempre" / "sin límite" no pueden caer en los defaults de TDLib (-1).
    @Test fun foreverAndNoLimitNeverDelete() {
        val r = autoCleanRequest(KeepMedia.FOREVER, CacheLimit.NONE)
        assertEquals(Int.MAX_VALUE, r.ttl)
        assertEquals(Long.MAX_VALUE, r.size)
        assertEquals(Int.MAX_VALUE, r.count)
    }

    @Test fun clearDeletesEverything() {
        val r = clearRequest()
        assertEquals(0L, r.size)
        assertEquals(0, r.ttl)
        assertEquals(0, r.immunityDelay)
    }

    @Test fun prefsKeysRoundTrip() {
        KeepMedia.entries.forEach { assertEquals(it, KeepMedia.of(it.key)) }
        CacheLimit.entries.forEach { assertEquals(it, CacheLimit.of(it.key)) }
        assertEquals(KeepMedia.WEEK, KeepMedia.of(null))
        assertEquals(CacheLimit.GB5, CacheLimit.of("x"))
    }
}
