package com.nimbo.data.transfer

import android.content.Context
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.prefs.AppPrefs
import com.nimbo.core.prefs.CacheLimit
import com.nimbo.core.prefs.KeepMedia
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// Caché de lo visto/escuchado (como Telegram): lo abierto se queda para no
// bajarlo otra vez, y se limpia solo al entrar según Ajustes (tiempo sin
// abrirlo + tope) o a mano. Todo vive en cacheDir (TDLib en cacheDir/tdlib).
@Singleton
class MediaCache @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val client: TdClient,
    private val prefs: AppPrefs,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    // Bytes en caché (null = sin calcular todavía).
    private val _used = MutableStateFlow<Long?>(null)
    val used: StateFlow<Long?> = _used
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing

    init {
        scope.launch { client.auth.collect { if (it == TgAuthState.READY) autoClean() } }
    }

    fun refresh() {
        scope.launch { measure() }
    }

    // Tras cambiar la política en Ajustes: se aplica ya.
    fun autoClean() {
        scope.launch {
            client.send(autoCleanRequest(prefs.keepMedia, prefs.cacheLimit))
            measure()
        }
    }

    // A mano: todo fuera menos subidas en curso y carátulas (las usa la lista).
    fun clear() {
        if (_clearing.value) return
        _clearing.value = true
        scope.launch {
            try {
                client.send(clearRequest())
                withContext(io) { OWN_DIRS.forEach { File(ctx.cacheDir, it).deleteRecursively() } }
                measure()
            } finally {
                _clearing.value = false
            }
        }
    }

    private suspend fun measure() {
        _used.value = withContext(io) { ctx.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    private companion object {
        // Copias propias: unidas/descifradas (PlainFiles) y audio del player.
        val OWN_DIRS = listOf("plain", "tg-player")
    }
}

// Lo abierto hace más de `keep` días, y lo más viejo hasta bajar del tope.
// Lo que se está usando ahora (última hora) no se toca.
fun autoCleanRequest(keep: KeepMedia, limit: CacheLimit): TdApi.OptimizeStorage = TdApi.OptimizeStorage(
    if (limit.gb > 0) limit.gb.toLong() shl 30 else Long.MAX_VALUE,
    if (keep.days > 0) keep.days * 86_400 else Int.MAX_VALUE,
    Int.MAX_VALUE, 3_600, emptyArray(), LongArray(0), LongArray(0), false, 0
)

fun clearRequest(): TdApi.OptimizeStorage =
    TdApi.OptimizeStorage(0, 0, 0, 0, emptyArray(), LongArray(0), LongArray(0), false, 0)
