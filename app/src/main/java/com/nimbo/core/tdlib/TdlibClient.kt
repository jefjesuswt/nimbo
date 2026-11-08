package com.nimbo.core.tdlib

import android.content.Context
import android.os.Build
import com.nimbo.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

// Una instancia por proceso: TDLib prohíbe 2 clientes sobre el mismo
// databaseDirectory (de ahí @Singleton).
@Singleton
class TdlibClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val secrets: SecretStore
) : TdClient {
    private val _auth = MutableStateFlow(TgAuthState.IDLE)
    override val auth: StateFlow<TgAuthState> = _auth

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error

    // ponytail: una entrada por archivo tocado en la sesión (bytes, no
    // contenido); se limpia al cerrar sesión.
    private val files = ConcurrentHashMap<Int, MutableStateFlow<TdApi.File?>>()

    @Volatile private var client: Client? = null
    @Volatile private var started = false

    @Synchronized
    override fun start(apiId: Int, apiHash: String) {
        if (started) return
        started = true
        val dir = File(ctx.filesDir, "tdlib").apply { mkdirs() }
        // Nube-first: lo bajado (streaming, visor, player) vive en la caché
        // (Android la cuenta como caché y la puede vaciar), no en los datos.
        val filesDir = File(ctx.cacheDir, "tdlib").apply { mkdirs() }
        // Pueden ser GB: fuera del hilo que arranca.
        Thread { purgeOldFiles(dir) }.start()
        val key = secrets.dbKey()
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(0)) }
        client = Client.create(
            { update -> onUpdate(apiId, apiHash, dir.absolutePath, filesDir.absolutePath, key, update) },
            { _error.value = "update failed" },
            { _error.value = it.message }
        )
    }

    override fun file(fileId: Int): StateFlow<TdApi.File?> =
        files.getOrPut(fileId) { MutableStateFlow(null) }

    private fun noteFile(f: TdApi.File) {
        files.getOrPut(f.id) { MutableStateFlow(null) }.value = f
    }

    // Antes los archivos iban junto a la base (files/tdlib/<tipo>/) y nunca
    // se borraban. Solo las carpetas de archivos de TDLib: la base se queda.
    private fun purgeOldFiles(dbDir: File) {
        OLD_FILE_DIRS.forEach { runCatching { File(dbDir, it).deleteRecursively() } }
    }

    private fun onUpdate(apiId: Int, apiHash: String, dbDir: String, filesDir: String, dbKey: ByteArray, update: TdApi.Object) {
        if (update is TdApi.UpdateFile) {
            noteFile(update.file)
            return
        }
        val state = (update as? TdApi.UpdateAuthorizationState)?.authorizationState ?: return
        when (state) {
            // Base cifrada: copiar files/tdlib no basta para robar la sesión.
            is TdApi.AuthorizationStateWaitTdlibParameters -> client?.send(
                TdApi.SetTdlibParameters(
                    false, dbDir, filesDir, dbKey,
                    true, true, true, false,
                    apiId, apiHash,
                    "en", "Android", "SDK ${Build.VERSION.SDK_INT}", BuildConfig.VERSION_NAME
                )
            ) { fail(it) }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TgAuthState.WAIT_PHONE
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TgAuthState.WAIT_CODE
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TgAuthState.WAIT_PASSWORD
            is TdApi.AuthorizationStateReady -> {
                _error.value = null
                _auth.value = TgAuthState.READY
            }
            // Logout/cierre: suelta instancia para poder re-loguear.
            is TdApi.AuthorizationStateClosed -> {
                client = null
                started = false
                files.clear()
                _auth.value = TgAuthState.IDLE
            }
            else -> {}
        }
    }

    override fun sendPhone(phone: String) {
        _error.value = null
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            false, false, false, false, false, null, null
        )
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, settings)) { fail(it) }
    }

    override fun sendCode(code: String) {
        _error.value = null
        client?.send(TdApi.CheckAuthenticationCode(code)) { fail(it) }
    }

    override fun sendPassword(password: String) {
        _error.value = null
        client?.send(TdApi.CheckAuthenticationPassword(password)) { fail(it) }
    }

    override fun logout() {
        client?.send(TdApi.LogOut()) { fail(it) }
    }

    override suspend fun send(fn: TdApi.Function<*>): TdApi.Object {
        val res = suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resume(TdApi.Error(0, "not started"))
                return@suspendCancellableCoroutine
            }
            try {
                c.send(fn, { res -> cont.resume(res) }, { e ->
                    cont.resume(TdApi.Error(0, e.message ?: "tdlib"))
                })
            } catch (e: Exception) {
                cont.resume(TdApi.Error(0, e.message ?: "send"))
            }
        }
        if (res is TdApi.File) noteFile(res)
        return res
    }

    private fun fail(obj: TdApi.Object) {
        if (obj is TdApi.Error) _error.value = obj.message
    }

    private companion object {
        val OLD_FILE_DIRS = listOf(
            "animations", "documents", "music", "photos", "profile_photos", "secret", "secret_thumbnails",
            "stickers", "stories", "temp", "thumbnails", "videos", "video_notes", "voice", "wallpapers",
            "notification_sounds", "passport"
        )
    }
}
