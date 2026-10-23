package com.nimbo

import com.nimbo.ui.telegram.Langs
import com.nimbo.ui.telegram.chunkText
import com.nimbo.ui.telegram.mlkitTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslatorTest {
    @Test fun mlkitTags() {
        assertEquals("zh", mlkitTag("zh-CN"))
        assertEquals("es", mlkitTag("es"))
        assertEquals("pt", mlkitTag("PT-br"))
        assertNull(mlkitTag("xx"))
        // Todo el picker existe en ML Kit.
        Langs.forEach { assertEquals(it, true, mlkitTag(it) != null) }
    }

    @Test fun chunks() {
        assertEquals(listOf("hola"), chunkText("hola", 10))
        val text = "aaaa bbbb\n\ncccc dddd"
        val parts = chunkText(text, 12)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertEquals(true, it.length <= 12) }
        assertEquals("aaaa bbbb", parts[0])
    }
}
