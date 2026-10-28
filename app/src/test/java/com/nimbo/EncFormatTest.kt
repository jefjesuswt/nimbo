package com.nimbo

import com.nimbo.data.cloud.splitPlan
import com.nimbo.data.crypto.DecryptingOutputStream
import com.nimbo.data.crypto.blockOf
import com.nimbo.data.crypto.cipherSize
import com.nimbo.data.crypto.deriveKek
import com.nimbo.data.crypto.encryptRange
import com.nimbo.data.crypto.newHeader
import com.nimbo.data.crypto.openText
import com.nimbo.data.crypto.plainSize
import com.nimbo.data.crypto.randomBytes
import com.nimbo.data.crypto.sealText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncFormatTest {
    private val key = randomBytes(32)
    private val block = 16

    private fun encrypt(plain: ByteArray, header: ByteArray = newHeader(4), k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        encryptRange(k, header, ByteArrayInputStream(plain), plain.size.toLong(), out, 0, Long.MAX_VALUE)
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, k: ByteArray = key, chunk: Int = 7): ByteArray {
        val out = ByteArrayOutputStream()
        DecryptingOutputStream(k, cipher.size.toLong(), out).use { d ->
            cipher.toList().chunked(chunk).forEach { d.write(it.toByteArray()) }
        }
        return out.toByteArray()
    }

    private fun fails(block: () -> Unit) {
        val ok = runCatching(block).exceptionOrNull().let { it is IOException || it is GeneralSecurityException }
        assertTrue("debía fallar", ok)
    }

    @Test fun roundTripAllSizes() {
        listOf(0, 1, 15, 16, 17, 32, 100).forEach { n ->
            val plain = ByteArray(n) { (it * 7).toByte() }
            val c = encrypt(plain)
            assertEquals(cipherSize(n.toLong(), block), c.size.toLong())
            assertEquals(n.toLong(), plainSize(c.size.toLong(), block))
            assertArrayEquals(plain, decrypt(c))
        }
    }

    // Partes cifradas por rango (spec 13 + 12) = cifrado entero.
    @Test fun rangesJoinIntoSameCipher() {
        val plain = ByteArray(100) { it.toByte() }
        val header = newHeader(4)
        val whole = encrypt(plain, header)
        val joined = ByteArrayOutputStream()
        splitPlan(whole.size.toLong(), 23).forEach { (off, len) ->
            encryptRange(key, header, ByteArrayInputStream(plain), plain.size.toLong(), joined, off, len)
        }
        assertArrayEquals(whole, joined.toByteArray())
        assertArrayEquals(plain, decrypt(joined.toByteArray()))
    }

    @Test fun realBlockSize() {
        assertEquals(1 shl 20, blockOf(newHeader()))
    }

    @Test fun wrongKeyFails() = fails { decrypt(encrypt(ByteArray(40)), k = randomBytes(32)) }

    @Test fun tamperFails() = fails {
        val c = encrypt(ByteArray(40))
        c[30] = (c[30] + 1).toByte()
        decrypt(c)
    }

    // Cortado justo en un borde de bloque: el flag de último lo detecta.
    @Test fun truncatedAtBlockFails() = fails {
        val c = encrypt(ByteArray(40))
        decrypt(c.copyOf(18 + 2 * (block + 16)))
    }

    @Test fun truncatedMidBlockFails() = fails { decrypt(encrypt(ByteArray(40)).copyOf(50)) }

    @Test fun notOursFails() = fails { decrypt(ByteArray(60)) }

    @Test fun sizeMismatchFails() = fails {
        encryptRange(key, newHeader(4), ByteArrayInputStream(ByteArray(10)), 20, ByteArrayOutputStream(), 0, Long.MAX_VALUE)
    }

    @Test fun textRoundTrip() {
        val s = sealText(key, "peli.mp4".toByteArray(), "name")
        assertEquals("peli.mp4", openText(key, s, "name")?.decodeToString())
        assertTrue(s.none { it.isWhitespace() || it == '#' })
        assertNull(openText(key, s, "otro uso"))
        assertNull(openText(randomBytes(32), s, "name"))
    }

    @Test fun kdfDependsOnPassword() {
        val salt = randomBytes(16)
        val a = deriveKek("una clave".toCharArray(), salt, 1000)
        assertArrayEquals(a, deriveKek("una clave".toCharArray(), salt, 1000))
        assertTrue(!a.contentEquals(deriveKek("otra clave".toCharArray(), salt, 1000)))
        assertEquals(32, a.size)
    }
}
