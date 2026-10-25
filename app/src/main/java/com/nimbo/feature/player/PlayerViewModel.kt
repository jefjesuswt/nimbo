package com.nimbo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nimbo.R
import com.nimbo.data.cloud.CloudOps
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.transfer.TransferManager
import com.nimbo.player.PlayerController
import com.nimbo.ui.common.UiText
import com.nimbo.ui.common.text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// Minibar + hoja del player. El estado de reproducción se lee directo del
// PlayerController (StateFlows); aquí van las acciones que tocan la nube.
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: PlayerController,
    private val ops: CloudOps,
    private val transfers: TransferManager
) : ViewModel() {
    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun download(f: TgFile) {
        viewModelScope.launch {
            _messages.send(text(if (transfers.download(f)) R.string.saved_to_downloads else R.string.download_failed))
        }
    }

    fun toggleFav() {
        _messages.trySend(text(if (player.toggleFav()) R.string.fav_added else R.string.fav_removed))
    }

    // Borra de Telegram; si sale bien, para el player (callback en main).
    fun delete(f: TgFile, onDeleted: () -> Unit) {
        viewModelScope.launch {
            val ok = ops.delete(f)
            if (ok) {
                player.stop()
                onDeleted()
            }
            _messages.send(text(if (ok) R.string.deleted else R.string.delete_failed))
        }
    }
}
