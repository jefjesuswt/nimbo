package com.nimbo

import com.nimbo.ui.telegram.Langs
import com.nimbo.ui.telegram.mlkitTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Solo build personal (ML Kit no está en el público).
class MlKitTagTest {
    @Test fun mlkitTags() {
        assertEquals("zh", mlkitTag("zh-CN"))
        assertEquals("es", mlkitTag("es"))
        assertEquals("pt", mlkitTag("PT-br"))
        assertNull(mlkitTag("xx"))
        // Todo el picker existe en ML Kit.
        Langs.forEach { assertEquals(it, true, mlkitTag(it) != null) }
    }
}
