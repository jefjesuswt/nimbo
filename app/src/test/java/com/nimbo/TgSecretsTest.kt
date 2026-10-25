package com.nimbo

import com.nimbo.core.tdlib.parseApiId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TgSecretsTest {
    @Test fun apiIdDigits() {
        assertEquals(12345678, parseApiId("12345678"))
        assertEquals(12345678, parseApiId(" 12 345 678 "))
        assertNull(parseApiId(""))
        assertNull(parseApiId("abc"))
        assertNull(parseApiId("99999999999")) // overflow Int
    }
}
