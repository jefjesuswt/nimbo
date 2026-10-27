package com.nimbo

import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.cloud.CloudOps
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.PlaybackPositions
import com.nimbo.fakes.FakeTdClient
import com.nimbo.fakes.MemoryStore
import com.nimbo.fakes.docMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudRepositoryTest {
    private val td = FakeTdClient()
    private val store = MemoryStore()

    private val scopes = mutableListOf<CoroutineScope>()

    // Scope "de la app" sobre el scheduler del test: advanceUntilIdle lo
    // corre (backgroundScope no).
    private fun TestScope.app(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @After fun tearDown() = scopes.forEach { it.cancel() }

    private fun TestScope.repo(): CloudRepository =
        CloudRepository(td, store, app(), StandardTestDispatcher(testScheduler))

    @Test fun loadsOnReadyAndIndexes() = runTest {
        td.savedMessages(docMessage(2, "a.pdf", "#nimbo/docs"), docMessage(1, "b.pdf"))
        val r = repo()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), r.files.value.map { it.messageId })
        assertEquals("docs", r.files.value[0].folder)
        assertEquals(2, store.rows.size)
    }

    // A2: cerrar sesión no deja nada de la cuenta anterior.
    @Test fun logoutClearsSession() = runTest {
        td.savedMessages(docMessage(1, "a.pdf"))
        val r = repo()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertEquals(1, r.files.value.size)
        td.auth.value = TgAuthState.WAIT_PHONE
        advanceUntilIdle()
        assertTrue(r.files.value.isEmpty())
        assertEquals(1, store.cleared)
        assertEquals(0L, r.myUserId)
        // Otra cuenta: vuelve a pedir el chat (no reutiliza el de antes).
        td.sent.clear()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertEquals(1, td.sentOf<TdApi.CreatePrivateChat>().size)
    }

    @Test fun undoWithinFiveSecondsKeepsFile() = runTest {
        td.savedMessages(docMessage(1, "a.pdf"))
        val r = repo()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        val token = r.deleteWithUndo(r.files.value)
        assertTrue(r.files.value.isEmpty())
        advanceTimeBy(3_000)
        r.undoDelete(token)
        advanceUntilIdle()
        assertEquals(1, r.files.value.size)
        assertTrue(td.sentOf<TdApi.DeleteMessages>().isEmpty())
    }

    @Test fun deleteAfterFiveSeconds() = runTest {
        td.savedMessages(docMessage(1, "a.pdf"))
        val r = repo()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        r.deleteWithUndo(r.files.value)
        advanceTimeBy(5_100)
        advanceUntilIdle()
        assertEquals(listOf(1L), td.sentOf<TdApi.DeleteMessages>().single().messageIds.toList())
        assertTrue(store.rows.isEmpty())
    }

    // Troceado: se lista uno y se borran todas sus partes.
    @Test fun splitFileListsOnceAndDeletesAllParts() = runTest {
        td.savedMessages(
            docMessage(2, "v.mp4.002", "#nimbo/x #part 2/2 g1"),
            docMessage(1, "v.mp4.001", "#nimbo/x #part 1/2 g1")
        )
        val r = repo()
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertEquals(listOf("v.mp4"), r.files.value.map { it.name })
        assertEquals(2, store.rows.values.single().parts.size)
        r.deleteWithUndo(r.files.value)
        advanceTimeBy(5_100)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), td.sentOf<TdApi.DeleteMessages>().single().messageIds.toSet())
    }

    // Mover = retag de carpeta, sin re-subir.
    @Test fun moveRetagsCaption() = runTest {
        val m = docMessage(1, "a.pdf", "#nimbo/old")
        td.savedMessages(m)
        td.on<TdApi.GetMessage> { m }
        val r = repo()
        val ops = CloudOps(td, r, app())
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        val moved = ops.move(r.files.value, "new/place")
        assertEquals(1, moved)
        assertEquals("#nimbo/new/place", td.sentOf<TdApi.EditMessageCaption>().single().caption.text)
    }

    // Antes los archivos directos de la carpeta acababan en "destino/carpeta".
    @Test fun renameFolderMovesDirectFiles() = runTest {
        val m = docMessage(1, "a.pdf", "#nimbo/music/rock")
        td.savedMessages(m)
        td.on<TdApi.GetMessage> { m }
        val r = repo()
        val ops = CloudOps(td, r, app())
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertTrue(ops.renameFolder("music/rock", "music/metal"))
        assertEquals("#nimbo/music/metal", td.sentOf<TdApi.EditMessageCaption>().single().caption.text)
    }

    @Test fun positionsReloadAndClearWithSession() = runTest {
        store.pos[5] = 90_000
        val p = PlaybackPositions(store, td, app(), StandardTestDispatcher(testScheduler))
        td.auth.value = TgAuthState.READY
        advanceUntilIdle()
        assertEquals(90_000L, p.positions.value[5])
        td.auth.value = TgAuthState.WAIT_PHONE
        advanceUntilIdle()
        assertTrue(p.positions.value.isEmpty())
    }
}
