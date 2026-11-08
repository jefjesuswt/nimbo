package com.nimbo.feature.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nimbo.R
import com.nimbo.core.di.IoDispatcher
import com.nimbo.data.cloud.CloudRepository
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.media.ThumbRepository
import com.nimbo.data.transfer.PlainFiles
import com.nimbo.data.transfer.TransferManager
import com.nimbo.feature.main.PhotoRoute
import com.nimbo.ui.common.UiText
import com.nimbo.ui.common.text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PhotoEvent {
    data class Message(val text: UiText) : PhotoEvent
    data class Share(val path: String, val name: String) : PhotoEvent
}

@HiltViewModel
class PhotoViewModel @Inject constructor(
    handle: SavedStateHandle,
    repo: CloudRepository,
    private val plain: PlainFiles,
    private val transfers: TransferManager,
    private val thumbs: ThumbRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val route = handle.toRoute<PhotoRoute>()
    val startIndex: Int = route.index

    // En el orden de la lista que se veía al abrir.
    val files: StateFlow<List<TgFile>> = repo.files.map { all ->
        val byId = all.associateBy { it.messageId }
        route.ids.mapNotNull { byId[it] }
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        repo.files.value.associateBy { it.messageId }.let { m -> route.ids.mapNotNull { m[it] } }
    )

    private val _events = Channel<PhotoEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Original a tamaño visor (2048 px).
    suspend fun full(f: TgFile): ImageBitmap? = withContext(io) {
        plain.path(f, 120_000)?.let(thumbs::full)
    }

    fun share(f: TgFile) {
        viewModelScope.launch {
            val path = plain.path(f, 60_000)
            _events.send(if (path == null) PhotoEvent.Message(text(R.string.download_failed)) else PhotoEvent.Share(path, f.name))
        }
    }

    fun download(f: TgFile) {
        viewModelScope.launch {
            _events.send(PhotoEvent.Message(text(if (transfers.download(f)) R.string.saved_to_downloads else R.string.download_failed)))
        }
    }
}
