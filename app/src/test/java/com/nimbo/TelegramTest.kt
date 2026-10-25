package com.nimbo

import com.nimbo.core.util.formatBytes
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.filterTgFiles
import com.nimbo.data.cloud.isTextFile
import com.nimbo.feature.auth.defaultCountry
import com.nimbo.feature.auth.fullPhone
import com.nimbo.feature.auth.isCodeOk
import com.nimbo.feature.auth.isPhoneOk
import com.nimbo.player.RepeatMode
import com.nimbo.player.artGlyphFor
import com.nimbo.player.nextRepeat
import com.nimbo.player.nextSpeed
import com.nimbo.player.nextTimer
import com.nimbo.player.resumeFrom
import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(2, filterTgFiles(mock, KindFilter.ALL).size)
        assertEquals(listOf("a"), filterTgFiles(mock, KindFilter.AUDIO).map { it.id })
        assertEquals(listOf("b"), filterTgFiles(mock, KindFilter.OTHER).map { it.id })
    }

    @Test fun countryPhone() {
        assertEquals("ES", defaultCountry().iso)
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
    }

    @Test fun playerCycles() {        assertEquals(RepeatMode.ALL, nextRepeat(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, nextRepeat(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, nextRepeat(RepeatMode.ONE))
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

    @Test fun resume() {
        val min = 60_000L
        assertEquals(0L, resumeFrom(90_000, 2 * min))          // corto: siempre desde 0
        assertEquals(90_000L, resumeFrom(90_000, 40 * min))    // largo: retoma
        assertEquals(0L, resumeFrom(40 * min - 10_000, 40 * min)) // casi terminado
        assertEquals(0L, resumeFrom(0, 40 * min))
        assertEquals(5 * min, resumeFrom(5 * min, 0))          // duración desconocida: retoma
    }

    @Test fun videoGestures() {
        assertEquals(0L, com.nimbo.feature.video.seekTarget(5_000, 60_000, -10_000))
        assertEquals(60_000L, com.nimbo.feature.video.seekTarget(55_000, 60_000, 10_000))
        assertEquals(30_000L, com.nimbo.feature.video.seekTarget(20_000, 0, 10_000))
        assertEquals(0.75f, com.nimbo.feature.video.dragLevel(0.5f, -250f, 1000f), 0.001f)
        assertEquals(1f, com.nimbo.feature.video.dragLevel(0.9f, -500f, 1000f), 0.001f)
        assertEquals(0f, com.nimbo.feature.video.dragLevel(0.1f, 500f, 1000f), 0.001f)
    }

    @Test fun subtitlePick() {
        val names = listOf("Movie.mp4", "movie.srt", "movie.es.srt", "other.srt", "movie.en.vtt", "notes.txt")
        assertEquals("movie.es.srt", com.nimbo.feature.video.pickSubtitle("Movie.mp4", names, "es"))
        assertEquals("movie.srt", com.nimbo.feature.video.pickSubtitle("Movie.mp4", names, "fr"))
        assertEquals("movie.en.vtt", com.nimbo.feature.video.pickSubtitle("Movie.mp4", names, "en"))
        assertEquals(null, com.nimbo.feature.video.pickSubtitle("clip.mp4", names, "es"))
    }

    @Test fun progress() {
        assertEquals(0.25f, com.nimbo.player.progressFrac(10 * 60_000L, 40 * 60), 0.001f)
        assertEquals(0f, com.nimbo.player.progressFrac(60_000L, 120), 0.001f)       // corto
        assertEquals(0f, com.nimbo.player.progressFrac(40 * 60_000L - 5_000, 40 * 60), 0.001f) // terminado
        assertEquals(0f, com.nimbo.player.progressFrac(60_000L, 0), 0.001f)         // sin duración
    }
}
