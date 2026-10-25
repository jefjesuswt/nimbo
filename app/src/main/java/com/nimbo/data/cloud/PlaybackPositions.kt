package com.nimbo.data.cloud

import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.data.cloud.local.CloudStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Continuar donde lo dejaste (spec 9a). En memoria para la barra de
// progreso de lista/grid; persistido en el índice.
@Singleton
class PlaybackPositions @Inject constructor(
    private val index: CloudStore,
    client: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val _positions = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val positions: StateFlow<Map<Long, Long>> = _positions

    init {
        // Con sesión: del índice. Al cerrarla el índice se vacía
        // (CloudRepository) y la memoria aquí.
        scope.launch {
            var wasReady = false
            client.auth.collect { a ->
                if (a == TgAuthState.READY) {
                    wasReady = true
                    withContext(io) { runCatching { _positions.value = index.positions() } }
                } else if (wasReady) {
                    wasReady = false
                    _positions.value = emptyMap()
                }
            }
        }
    }

    fun save(messageId: Long, ms: Long) {
        _positions.update { if (ms > 0) it + (messageId to ms) else it - messageId }
        scope.launch(io) { runCatching { index.savePos(messageId, ms) } }
    }

    suspend fun of(messageId: Long): Long =
        _positions.value[messageId] ?: withContext(io) { runCatching { index.pos(messageId) }.getOrDefault(0L) }
}
