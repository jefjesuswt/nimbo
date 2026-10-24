package com.nimbo

import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.SortBy
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.feature.auth.AuthStep
import com.nimbo.feature.auth.authStepOf
import com.nimbo.feature.cloud.Browse
import com.nimbo.feature.cloud.EmptyKind
import com.nimbo.feature.cloud.buildListing
import com.nimbo.feature.cloud.photoQueue
import com.nimbo.feature.cloud.playQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchitectureTest {
    private fun f(id: Long, name: String, folder: String = "", kind: TgKind = TgKind.DOC, title: String = "") =
        TgFile(id.toString(), name, kind, "", 1, messageId = id, folder = folder, dateSec = id.toInt(), title = title)

    // A1: renombrar el título o deducir la cover cambia la igualdad (el
    // StateFlow emite y la UI se entera).
    @Test fun equalityCoversAllFields() {
        val a = f(1, "a.mp3", kind = TgKind.AUDIO)
        assertNotEquals(a, a.copy(title = "Nuevo"))
        assertNotEquals(a, a.copy(coverHash = "abc"))
        assertNotEquals(a, a.copy(thumbMini = byteArrayOf(1)))
        assertEquals(a.copy(thumbMini = byteArrayOf(1, 2)), a.copy(thumbMini = byteArrayOf(1, 2)))
    }

    @Test fun authSteps() {
        assertEquals(AuthStep.Loading, authStepOf(false, true, TgAuthState.READY, false))
        assertEquals(AuthStep.Keys, authStepOf(true, false, TgAuthState.READY, false))
        assertEquals(AuthStep.Loading, authStepOf(true, true, TgAuthState.IDLE, false))
        assertEquals(AuthStep.Phone, authStepOf(true, true, TgAuthState.WAIT_PHONE, false))
        assertEquals(AuthStep.Code, authStepOf(true, true, TgAuthState.WAIT_CODE, false))
        // "Número equivocado" vuelve al teléfono aunque TDLib siga en WAIT_CODE.
        assertEquals(AuthStep.Phone, authStepOf(true, true, TgAuthState.WAIT_CODE, true))
        assertEquals(AuthStep.Password, authStepOf(true, true, TgAuthState.WAIT_PASSWORD, false))
        assertEquals(AuthStep.Ready, authStepOf(true, true, TgAuthState.READY, false))
    }

    private val tree = listOf(
        f(1, "root.pdf"),
        f(2, "a.mp3", "music/rock", TgKind.AUDIO, "Zeta"),
        f(3, "b.mp3", "music/rock", TgKind.AUDIO, "Alfa"),
        f(4, "c.jpg", "pics", TgKind.IMAGE),
        f(5, "d.jpg", "pics/2024", TgKind.IMAGE)
    )
    private val byName = SortKey(SortBy.NAME, true)

    @Test fun filesListingFoldersThenFiles() {
        val l = buildListing(CloudTab.FILES, tree, setOf("empty"), Browse(sort = byName))
        assertEquals(listOf("empty", "music", "pics"), l.subs)
        assertEquals(listOf("root.pdf"), l.files.map { it.name })
        assertNull(l.albums)
        val rock = buildListing(CloudTab.FILES, tree, emptySet(), Browse(dir = "music/rock", sort = byName))
        assertEquals(listOf("Alfa", "Zeta"), rock.files.map { it.title })
        assertEquals(true, rock.showCrumb)
        val media = buildListing(CloudTab.FILES, tree, emptySet(), Browse(dir = "pics", filter = KindFilter.AUDIO, sort = byName))
        assertEquals(listOf("pics/2024"), media.subs)
        assertEquals(emptyList<TgFile>(), media.files)
        assertEquals(EmptyKind.CLOUD_EMPTY, buildListing(CloudTab.FILES, emptyList(), emptySet(), Browse(sort = byName)).empty)
    }

    @Test fun searchCoversWholeTree() {
        val l = buildListing(CloudTab.FILES, tree, emptySet(), Browse(query = "ALFA", sort = byName))
        assertEquals(listOf("b.mp3"), l.files.map { it.name })
        val none = buildListing(CloudTab.FILES, tree, emptySet(), Browse(query = "nada", sort = byName))
        assertEquals(EmptyKind.NO_RESULTS, none.empty)
    }

    @Test fun musicShowsAlbumsUntilOpened() {
        val l = buildListing(CloudTab.AUDIO, tree, emptySet(), Browse(sort = byName))
        assertEquals(listOf("music/rock"), l.albums!!.map { it.folder })
        val open = buildListing(CloudTab.AUDIO, tree, emptySet(), Browse(album = "music/rock", sort = byName))
        assertNull(open.albums)
        assertEquals("rock", open.albumName)
        assertEquals(listOf("Alfa", "Zeta"), open.files.map { it.title })
    }

    @Test fun queues() {
        val track = tree[1]
        assertEquals(listOf(2L, 3L), playQueue(CloudTab.FILES, tree, null, track).map { it.messageId })
        // Archivos: galería = álbum raíz del dir con sus subcarpetas.
        assertEquals(listOf(4L, 5L), photoQueue(CloudTab.FILES, tree, "pics/2024", null, tree[4]).map { it.messageId })
        assertEquals(listOf(5L), photoQueue(CloudTab.PHOTOS, tree, "", "pics/2024", tree[4]).map { it.messageId })
    }
}
