package com.nimbo.data.storage

import android.content.Context
import android.util.Log
import com.nimbo.core.di.ApplicationScope
import com.nimbo.core.di.IoDispatcher
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TgAuthState
import com.nimbo.core.tdlib.call
import com.nimbo.core.tdlib.orNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// Lo que ocupa Nimbo en el teléfono: archivos bajados por TDLib y caché propia.
data class StorageUsage(val tdlibFiles: Long, val cache: Long) {
    val total get() = tdlibFiles + cache
}

// Lógica sin Android (testeable con un TdClient falso y carpetas temporales).
class StorageOps(
    private val client: TdClient,
    private val tdlibDir: File,
    private val cacheDir: File
) {
    private val ready get() = client.auth.value == TgAuthState.READY

    // Sin sesión TDLib no contesta: se mide la carpeta (incluye la base).
    suspend fun usage(): StorageUsage {
        val td = if (ready) client.call<TdApi.StorageStatisticsFast>(TdApi.GetStorageStatisticsFast()).orNull()?.filesSize else null
        return StorageUsage(td ?: dirSize(tdlibDir), dirSize(cacheDir))
    }

    // Recorte automático: tope a TDLib; la caché propia ya tiene sus LRU.
    suspend fun trim() {
        if (ready) client.send(StoragePolicy.autoTrim())
    }

    // Botón de Ajustes. Los archivos de TDLib solo se borran por TDLib (su
    // base los indexa); las subidas en curso y busy (carpetas de cacheDir en
    // uso, p. ej. la del player) se respetan.
    suspend fun clear(busy: Set<String> = emptySet()) {
        if (ready) client.send(StoragePolicy.clearAll())
        clearDir(cacheDir, busy + StoragePolicy.UPLOAD_DIR)
    }
}

@Singleton
class StorageCleaner @Inject constructor(
    @ApplicationContext ctx: Context,
    private val client: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val ops = StorageOps(client, File(ctx.filesDir, "tdlib"), ctx.cacheDir)

    // Desde Application.onCreate: recorta al entrar y cada media hora.
    fun start() {
        scope.launch {
            client.auth.collectLatest { st ->
                if (st != TgAuthState.READY) return@collectLatest
                while (true) {
                    runCatching { withContext(io) { ops.trim() } }.onFailure { Log.w(TAG, "trim", it) }
                    delay(StoragePolicy.TRIM_EVERY_MS)
                }
            }
        }
    }

    suspend fun usage(): StorageUsage = withContext(io) { ops.usage() }

    suspend fun clear(busy: Set<String> = emptySet()) = withContext(io) { ops.clear(busy) }

    private companion object {
        const val TAG = "Storage"
    }
}
