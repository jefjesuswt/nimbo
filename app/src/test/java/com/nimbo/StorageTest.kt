package com.nimbo

import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.storage.StorageOps
import com.nimbo.data.storage.StoragePolicy
import com.nimbo.data.storage.clearDir
import com.nimbo.data.storage.dirSize
import com.nimbo.fakes.FakeTdClient
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    private val td = FakeTdClient()
    private val root = Files.createTempDirectory("nimbo-storage").toFile().apply { deleteOnExit() }
    private val tdlib = File(root, "tdlib")
    private val cache = File(root, "cache")

    private fun put(dir: File, name: String, bytes: Int) =
        File(dir, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun ops() = StorageOps(td, tdlib, cache)

    // El recorte automático pone tope y caducidad explícitos (nunca -1:
    // el valor por defecto de TDLib no es el nuestro).
    @Test fun autoTrimHasExplicitCap() {
        val r = StoragePolicy.autoTrim()
        assertEquals(StoragePolicy.TDLIB_CAP, r.size)
        assertEquals(StoragePolicy.TDLIB_TTL_SEC, r.ttl)
        assertTrue(r.count > 0)
        assertTrue(r.immunityDelay > 0)
        assertEquals(0, r.fileTypes.size)
    }

    @Test fun clearAllRemovesEverythingNotInUse() {
        val r = StoragePolicy.clearAll()
        assertEquals(0L, r.size)
        assertEquals(0, r.ttl)
        assertEquals(0, r.count)
        assertEquals(0, r.immunityDelay)
    }

    @Test fun dirSizeSumsTree() {
        put(cache, "a/b/c.bin", 10)
        put(cache, "d.bin", 5)
        assertEquals(15L, dirSize(cache))
        assertEquals(0L, dirSize(File(root, "nope")))
    }

    @Test fun clearDirKeepsListed() {
        put(cache, "plain/x.bin", 100)
        put(cache, "tg-upload/1/part", 50)
        assertEquals(100L, clearDir(cache, setOf("tg-upload")))
        assertFalse(File(cache, "plain").exists())
        assertTrue(File(cache, "tg-upload/1/part").exists())
    }

    // Sin sesión no se le habla a TDLib: se mide la carpeta y no se borra
    // nada de ella (sus archivos los indexa su base).
    @Test fun offlineMeasuresFolderAndOnlyClearsCache() = runTest {
        put(tdlib, "videos/v.mp4", 300)
        put(cache, "tg-covers/c.jpg", 20)
        val o = ops()
        assertEquals(300L, o.usage().tdlibFiles)
        assertEquals(20L, o.usage().cache)
        o.trim()
        o.clear()
        assertTrue(td.sent.isEmpty())
        assertTrue(File(tdlib, "videos/v.mp4").exists())
        assertEquals(0L, dirSize(cache))
    }

    @Test fun readyAsksTdlib() = runTest {
        td.auth.value = TgAuthState.READY
        td.on<TdApi.GetStorageStatisticsFast> { TdApi.StorageStatisticsFast(9_000L, 3, 1L, 0L, 0L) }
        put(cache, "tg-player/1.mp3", 40)
        put(cache, "plain/2.pdf", 60)
        val o = ops()
        assertEquals(9_000L, o.usage().tdlibFiles)

        o.trim()
        assertEquals(StoragePolicy.TDLIB_CAP, td.sentOf<TdApi.OptimizeStorage>().single().size)

        o.clear(busy = setOf("tg-player"))
        assertEquals(0L, td.sentOf<TdApi.OptimizeStorage>().last().size)
        // Lo que suena sigue; lo demás fuera.
        assertTrue(File(cache, "tg-player/1.mp3").exists())
        assertFalse(File(cache, "plain").exists())
    }
}
