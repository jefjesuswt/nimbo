package com.nimbo

import com.nimbo.core.util.trimCache
import com.nimbo.data.cloud.PartTag
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.TgPart
import com.nimbo.data.cloud.groupParts
import com.nimbo.data.cloud.incomplete
import com.nimbo.data.cloud.mapMessage
import com.nimbo.data.cloud.messageIds
import com.nimbo.data.cloud.parseFolder
import com.nimbo.data.cloud.parsePart
import com.nimbo.data.cloud.partAt
import com.nimbo.data.cloud.partName
import com.nimbo.data.cloud.splitPlan
import com.nimbo.data.cloud.tagFolder
import com.nimbo.data.cloud.tagPart
import com.nimbo.data.transfer.copyRange
import com.nimbo.fakes.docMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsTest {
    @Test fun planSplitsOnlyWhenNeeded() {
        assertEquals(emptyList<Pair<Long, Long>>(), splitPlan(10, 10))
        assertEquals(listOf(0L to 4L, 4L to 4L, 8L to 2L), splitPlan(10, 4))
        assertEquals(listOf(0L to 5L, 5L to 5L), splitPlan(10, 5))
    }

    @Test fun partTagRoundTrip() {
        val cap = tagPart("#nimbo/videos", PartTag(2, 3, "ab12"))
        assertEquals("#nimbo/videos #part 2/3 ab12", cap)
        assertEquals(PartTag(2, 3, "ab12"), parsePart(cap))
        assertEquals("videos", parseFolder(cap))
        // Mover carpeta conserva el tag de parte.
        assertEquals(PartTag(2, 3, "ab12"), parsePart(tagFolder(cap, "otra")))
        assertNull(parsePart("#nimbo/x #part 4/3 ab12"))
        assertNull(parsePart("#nimbo/x"))
        assertEquals("peli.mp4.001", partName("peli.mp4", 1))
    }

    @Test fun mapsPartAsBaseFile() {
        val f = mapMessage(docMessage(5, "peli.mp4.002", "#nimbo/v #part 2/2 g1"))!!
        assertEquals("peli.mp4", f.name)
        assertEquals(TgKind.VIDEO, f.kind)
        assertEquals(listOf(TgPart(2, 5, 5, 10)), f.parts)
        // Sin tag, el .001 es un nombre más.
        assertEquals("x.001", mapMessage(docMessage(6, "x.001"))!!.name)
    }

    @Test fun groupsPartsIntoOneFile() {
        val ms = listOf(
            docMessage(12, "peli.mp4.002", "#nimbo/v #part 2/2 g1"),
            docMessage(11, "peli.mp4.001", "#nimbo/v #part 1/2 g1"),
            docMessage(13, "otro.txt", "#nimbo/v"),
            // Mismo grupo en otra carpeta (copia): archivo aparte.
            docMessage(14, "peli.mp4.001", "#nimbo/w #part 1/2 g1")
        ).mapNotNull(::mapMessage)
        val out = groupParts(ms)
        assertEquals(3, out.size)
        val peli = out.first { it.folder == "v" && it.name == "peli.mp4" }
        assertEquals(11L, peli.messageId)
        assertEquals(20L, peli.sizeBytes)
        assertEquals(listOf(11L, 12L), peli.messageIds())
        assertFalse(peli.incomplete())
        val copy = out.first { it.folder == "w" }
        assertTrue(copy.incomplete())
        assertEquals(listOf(13L), out.first { it.name == "otro.txt" }.messageIds())
    }

    @Test fun copyRangeRebuildsOriginal() {
        val src = ByteArray(1000) { it.toByte() }
        val joined = ByteArrayOutputStream()
        splitPlan(src.size.toLong(), 300).forEach { (off, len) ->
            copyRange(ByteArrayInputStream(src), joined, off, len)
        }
        assertTrue(src.contentEquals(joined.toByteArray()))
    }

    @Test fun offsetToPart() {
        val sizes = listOf(4L, 4L, 2L)
        assertEquals(0 to 0L, partAt(0, sizes))
        assertEquals(0 to 3L, partAt(3, sizes))
        assertEquals(1 to 0L, partAt(4, sizes))
        assertEquals(2 to 1L, partAt(9, sizes))
        assertEquals(3 to 0L, partAt(10, sizes))
    }

    // Una parte unida > tope de la caché no se borra recién escrita.
    @Test
    fun trimKeepsNewest() {
        val dir = createTempDirectory().toFile()
        val old = File(dir, "old").apply { writeBytes(ByteArray(10)); setLastModified(1_000) }
        val big = File(dir, "big").apply { writeBytes(ByteArray(30)); setLastModified(2_000) }
        trimCache(dir, 20)
        assertFalse(old.exists())
        assertTrue(big.exists())
        dir.deleteRecursively()
    }
}
