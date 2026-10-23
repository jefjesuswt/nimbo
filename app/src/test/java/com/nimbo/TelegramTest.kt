package com.nimbo

import com.nimbo.ui.telegram.TgFile
import com.nimbo.ui.telegram.TgKind
import com.nimbo.ui.telegram.artGlyphFor
import com.nimbo.ui.telegram.defaultCountry
import com.nimbo.ui.telegram.filterTgFiles
import com.nimbo.ui.telegram.formatBytes
import com.nimbo.ui.telegram.fullPhone
import com.nimbo.ui.telegram.isCodeOk
import com.nimbo.ui.telegram.isPhoneOk
import com.nimbo.ui.telegram.isTextFile
import com.nimbo.ui.telegram.nextRepeat
import com.nimbo.ui.telegram.nextSpeed
import com.nimbo.ui.telegram.nextTimer
import com.nimbo.ui.telegram.tgStats
import org.junit.Test
import org.junit.Assert.assertEquals

class TelegramTest {
    private val mock = listOf(
        TgFile("a", "x.mp3", TgKind.AUDIO, ""),
        TgFile("b", "y.pdf", TgKind.DOC, "")
    )

    @Test fun phone() {
        assertEquals(false, isPhoneOk(""))
        assertEquals(false, isPhoneOk("12345"))
        assertEquals(true, isPhoneOk("612345678"))
        assertEquals(true, isPhoneOk("+34 612 345 678"))
    }

    @Test fun code() {
        assertEquals(false, isCodeOk("1234"))
        assertEquals(false, isCodeOk("00000"))
        assertEquals(true, isCodeOk("12345"))
    }

    @Test fun filters() {
        assertEquals(2, filterTgFiles(mock, "all").size)
        assertEquals(listOf("a"), filterTgFiles(mock, "audio").map { it.id })
        assertEquals(listOf("b"), filterTgFiles(mock, "other").map { it.id })
    }

    @Test fun countryPhone() {
        assertEquals("España", defaultCountry().name)
        assertEquals("525512345678", fullPhone("52", "5512345678"))
        assertEquals("5212345678", fullPhone("52", "012345678")) // cero troncal fuera
        assertEquals("15551234567", fullPhone("1", "(555) 123-4567"))
    }

    @Test fun stats() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes((1.5 * (1L shl 20)).toLong()))
        assertEquals("2.0 GB", formatBytes(2L shl 30))
        val two = listOf(
            TgFile("a", "x.mp3", TgKind.AUDIO, "", 1L shl 20),
            TgFile("b", "y.pdf", TgKind.DOC, "", 1L shl 20)
        )
        assertEquals("2 files · 2.0 MB", tgStats(two))
    }

    @Test fun playerCycles() {        assertEquals("all", nextRepeat("off"))
        assertEquals("one", nextRepeat("all"))
        assertEquals("off", nextRepeat("one"))
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(2f, nextSpeed(1.5f))
        assertEquals(1f, nextSpeed(2f))
        assertEquals(5, nextTimer(0))
        assertEquals(30, nextTimer(15))
        assertEquals(0, nextTimer(30))
        assertEquals("podcasts", artGlyphFor("Podcast-Ep14.mp3"))
        assertEquals("mic", artGlyphFor("Voice memo.ogg"))
        assertEquals("school", artGlyphFor("Lecture-07.mp3"))
        assertEquals("music_note", artGlyphFor("song.flac"))
    }

    @Test fun textFiles() {
        assertEquals(true, isTextFile("notes.txt"))
        assertEquals(true, isTextFile("SUBS.SRT"))
        assertEquals(true, isTextFile("doc.md"))
        assertEquals(false, isTextFile("song.mp3"))
        assertEquals(false, isTextFile("noext"))
        assertEquals(false, isTextFile("pic.jpg"))
    }
}
