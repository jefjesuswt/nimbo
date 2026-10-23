package com.nimbo

import com.nimbo.backup.DocEntry
import com.nimbo.backup.backupFolder
import com.nimbo.backup.floodWaitSec
import com.nimbo.backup.isMediaName
import com.nimbo.backup.newEntries
import org.junit.Assert.assertEquals
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
}
