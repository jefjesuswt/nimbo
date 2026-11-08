package com.nimbo.feature.video

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.transfer.PlainFiles
import com.nimbo.feature.main.VideoRoute
import com.nimbo.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

// Vídeo (spec 08): cola = vídeos de la misma carpeta, por nombre.
@HiltViewModel
class VideoViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: CloudRepository,
    private val plain: PlainFiles,
    val player: PlayerController
) : ViewModel() {
    private val messageId = handle.toRoute<VideoRoute>().messageId

    fun start() {
        val all = repo.files.value
        val f = all.firstOrNull { it.messageId == messageId } ?: return
        val q = all.filter { it.kind == TgKind.VIDEO && it.folder == f.folder }.sortedBy { it.name.lowercase() }
            .ifEmpty { listOf(f) }
        player.play(q, q.indexOfFirst { it.id == f.id }.coerceAtLeast(0))
    }

    fun stop() = player.stop()

    // Subtítulo hermano (.srt/.vtt) del track actual → path local.
    suspend fun subtitleFor(f: TgFile): Pair<String, Boolean>? {
        val siblings = repo.files.value.filter { it.folder == f.folder && it.kind == TgKind.DOC }
        val name = pickSubtitle(f.name, siblings.map { it.name }, Locale.getDefault().language) ?: return null
        val sub = siblings.first { it.name == name }
        val path = plain.path(sub, 60_000) ?: return null
        return path to name.endsWith(".vtt", ignoreCase = true)
    }
}
