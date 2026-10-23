package com.nimbo

import com.nimbo.ui.telegram.chunkText
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorTest {
    @Test fun chunks() {
        assertEquals(listOf("hola"), chunkText("hola", 10))
        val text = "aaaa bbbb\n\ncccc dddd"
        val parts = chunkText(text, 12)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertEquals(true, it.length <= 12) }
        assertEquals("aaaa bbbb", parts[0])
    }
}
