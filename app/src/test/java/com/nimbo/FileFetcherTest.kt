package com.nimbo

import com.nimbo.data.transfer.FileFetcher
import com.nimbo.fakes.FakeTdClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileFetcherTest {
    private val td = FakeTdClient()

    private fun file(id: Int, done: Boolean, got: Long, path: String = "") = TdApi.File().apply {
        this.id = id
        size = 100
        local = TdApi.LocalFile().apply {
            this.path = path
            isDownloadingCompleted = done
            downloadedSize = got
        }
        remote = TdApi.RemoteFile()
    }

    // A3: el "completado" llegó antes de empezar a esperar: no se pierde.
    @Test fun completionBeforeWaitIsKept() = runTest {
        val tmp = java.io.File.createTempFile("nimbo", ".bin").apply { deleteOnExit() }
        td.emitFile(file(9, true, 100, tmp.path))
        assertEquals(tmp.path, FileFetcher(td).awaitFile(9, 1_000))
    }

    @Test fun reportsProgressUntilDone() = runTest {
        val tmp = java.io.File.createTempFile("nimbo", ".bin").apply { deleteOnExit() }
        val seen = mutableListOf<Float>()
        val r = async { FileFetcher(td).awaitFile(9, 10_000) { seen += it } }
        runCurrent()
        td.emitFile(file(9, false, 50))
        runCurrent()
        td.emitFile(file(9, true, 100, tmp.path))
        assertEquals(tmp.path, r.await())
        assertEquals(listOf(0f, 0.5f), seen)
    }

    // Sin progreso en stallMs → null (no cuelga para siempre).
    @Test fun stallTimesOut() = runTest {
        val r = async { FileFetcher(td).awaitFile(9, 1_000) }
        advanceTimeBy(1_500)
        assertNull(r.await())
    }
}
