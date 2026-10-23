package com.nimbo

import com.nimbo.backup.DocEntry
import com.nimbo.backup.backupFolder
import com.nimbo.backup.floodWaitSec
import com.nimbo.backup.isMediaName
import com.nimbo.backup.newEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupTest {
    @Test fun mediaFilter() {
        assertEquals(true, isMediaName("IMG_0001.JPG"))
        assertEquals(true, isMediaName("clip.mp4"))
        assertEquals(true, isMediaName("photo.heic"))
        assertEquals(false, isMediaName("notes.txt"))
        assertEquals(false, isMediaName("noext"))
    }

    @Test fun onlyNewOrChanged() {
        val a = DocEntry("u/a", "a.jpg", 10, 100)
        val b = DocEntry("u/b", "b.jpg", 20, 200)
        val c = DocEntry("u/c", "c.mp4", 30, 300)
        val log = mapOf("u/a" to (10L to 100L), "u/b" to (20L to 150L))
        // a ya subido; b editado (otra fecha); c nuevo.
        assertEquals(listOf("u/b", "u/c"), newEntries(listOf(a, b, c), log).map { it.uri })
        assertEquals(emptyList<DocEntry>(), newEntries(listOf(a), log))
    }

    @Test fun destination() {
        assertEquals("Backup/Camera", backupFolder("Camera"))
        assertEquals("Backup/DCIM/Screenshots", backupFolder("DCIM", "Screenshots"))
        assertEquals("Backup/Phone", backupFolder(""))
    }

    @Test fun floodWait() {
        assertEquals(30, floodWaitSec("FLOOD_WAIT_30"))
        assertEquals(7, floodWaitSec("Too Many Requests: retry after 7"))
        assertEquals(null, floodWaitSec("FILE_PART_INVALID"))
        assertEquals(null, floodWaitSec(null))
    }

    @Test fun dedupeAndRetry() {
        val a = com.nimbo.backup.fingerprint(10, byteArrayOf(1, 2), byteArrayOf(3))
        assertEquals(a, com.nimbo.backup.fingerprint(10, byteArrayOf(1, 2), byteArrayOf(3)))
        assertNotEquals(a, com.nimbo.backup.fingerprint(11, byteArrayOf(1, 2), byteArrayOf(3)))
        assertNotEquals(a, com.nimbo.backup.fingerprint(10, byteArrayOf(1, 2), byteArrayOf(4)))
        val f = com.nimbo.ui.telegram.TgFile("1", "IMG_1.jpg", com.nimbo.ui.telegram.TgKind.IMAGE, "", 500, folder = "Backup/Camera")
        assertEquals(f, com.nimbo.backup.inCloud(listOf(f), "Backup/Camera", "IMG_1.jpg", 500))
        assertEquals(null, com.nimbo.backup.inCloud(listOf(f), "Backup/Camera", "IMG_1.jpg", 501))
        assertEquals(null, com.nimbo.backup.inCloud(listOf(f), "Backup/Other", "IMG_1.jpg", 500))
        val h = 3_600_000L
        assertEquals(true, com.nimbo.backup.shouldRetry(0, 0, 0))
        assertEquals(false, com.nimbo.backup.shouldRetry(1, 0, h - 1))
        assertEquals(true, com.nimbo.backup.shouldRetry(1, 0, h))
        assertEquals(false, com.nimbo.backup.shouldRetry(3, 0, 3 * h))      // 3 fallos: espera 4 h
        assertEquals(true, com.nimbo.backup.shouldRetry(3, 0, 4 * h))
        assertEquals(false, com.nimbo.backup.shouldRetry(com.nimbo.backup.MAX_FAILS, 0, 1000 * h))
    }
}
