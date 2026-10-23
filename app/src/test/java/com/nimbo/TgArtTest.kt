package com.nimbo

import com.nimbo.ui.telegram.TgArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TgArtTest {
    private val img = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    private fun sync(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte()
    )

    @Test fun id3Apic() {
        val body = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + img
        val frame = "APIC".toByteArray() + be32(body.size) + byteArrayOf(0, 0) + body
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            sync(frame.size) + frame
        assertEquals(img.toList(), TgArt.id3Apic(tag)!!.toList())
        assertEquals(img.toList(), TgArt.picture(tag)!!.toList())
        assertNull(TgArt.id3Apic(byteArrayOf(1, 2, 3)))
        assertNull(TgArt.id3Apic("ID3\u0003\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray()))
    }

    @Test fun flacPicture() {
        val mime = "image/jpeg".toByteArray()
        val block = be32(mime.size) + mime + be32(0) + be32(800) + be32(600) +
            be32(24) + be32(0) + be32(img.size) + img
        val hdr = byteArrayOf((0x80 or 6).toByte()) + byteArrayOf(
            (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()
        )
        val flac = "fLaC".toByteArray() + hdr + block
        assertEquals(img.toList(), TgArt.flacPicture(flac)!!.toList())
        assertEquals(img.toList(), TgArt.picture(flac)!!.toList())
        assertNull(TgArt.flacPicture("fLaC".toByteArray()))
    }

    @Test fun mp4Covr() {
        fun atom(name: String, extra: ByteArray = byteArrayOf(), vararg kids: ByteArray): ByteArray {
            val body = extra + (kids.reduceOrNull { a, b -> a + b } ?: byteArrayOf())
            return be32(8 + body.size) + name.toByteArray(Charsets.ISO_8859_1) + body
        }
        // data: tipo jpeg(13) + locale + imagen.
        val data = atom("data", byteArrayOf(0, 0, 0, 13, 0, 0, 0, 0) + img)
        val moov = atom("moov", kids = arrayOf(atom("udta", kids = arrayOf(
            atom("meta", extra = byteArrayOf(0, 0, 0, 0), kids = arrayOf(
                atom("ilst", kids = arrayOf(atom("covr", kids = arrayOf(data))))
            ))
        ))))
        assertEquals(img.toList(), TgArt.mp4Covr(moov)!!.toList())
        assertEquals(img.toList(), TgArt.picture(moov)!!.toList())
        // moov sin covr → null.
        assertNull(TgArt.mp4Covr(atom("moov")))
    }
}
