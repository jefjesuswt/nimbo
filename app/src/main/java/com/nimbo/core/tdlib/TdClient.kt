package com.nimbo.core.tdlib

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

// Estado auth TDLib. La UI solo observa; TDLib manda vía updates.
enum class TgAuthState { IDLE, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY }

sealed interface TdResult<out T> {
    data class Ok<T>(val value: T) : TdResult<T>
    data class Err(val code: Int, val message: String) : TdResult<Nothing>
}

fun <T> TdResult<T>.orNull(): T? = (this as? TdResult.Ok)?.value

// Costura sobre TDLib: la impl real envuelve Client; los tests usan un fake.
interface TdClient {
    val auth: StateFlow<TgAuthState>
    val error: StateFlow<String?>

    fun start(apiId: Int, apiHash: String)
    fun sendPhone(phone: String)
    fun sendCode(code: String)
    fun sendPassword(password: String)
    fun logout()

    // Respuesta cruda: TdApi.Error si falla (sin cliente, red, TDLib).
    suspend fun send(fn: TdApi.Function<*>): TdApi.Object

    // Último estado conocido del archivo. Es un StateFlow por id que se
    // actualiza con cada UpdateFile: quien espera "completado" lo ve aunque
    // el update llegara antes de empezar a esperar o en plena ráfaga.
    fun file(fileId: Int): StateFlow<TdApi.File?>
}

// Llamada tipada: Ok si la respuesta es T, Err si es Error u otro tipo.
suspend inline fun <reified T : TdApi.Object> TdClient.call(fn: TdApi.Function<*>): TdResult<T> =
    when (val r = send(fn)) {
        is T -> TdResult.Ok(r)
        is TdApi.Error -> TdResult.Err(r.code, r.message)
        else -> TdResult.Err(0, "unexpected ${r.javaClass.simpleName}")
    }

suspend fun TdClient.ok(fn: TdApi.Function<*>): Boolean = send(fn) !is TdApi.Error

// Espera a que el archivo cumpla `done` (o timeout → null).
suspend fun TdClient.awaitFile(fileId: Int, timeoutMs: Long, done: (TdApi.File) -> Boolean): TdApi.File? =
    withTimeoutOrNull(timeoutMs) { file(fileId).filterNotNull().first(done) }

fun TdApi.File.localPathOrNull(): String? =
    local?.path?.takeIf { it.isNotEmpty() && java.io.File(it).exists() }

// Id del archivo principal de un mensaje (doc/audio/vídeo/foto mayor).
fun TdApi.MessageContent.mainFileId(): Int = when (this) {
    is TdApi.MessageDocument -> document.document.id
    is TdApi.MessageAudio -> audio.audio.id
    is TdApi.MessageVideo -> video.video.id
    is TdApi.MessagePhoto -> photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id ?: 0
    else -> 0
}

fun TdApi.MessageContent.captionText(): String? = when (this) {
    is TdApi.MessageAudio -> caption?.text
    is TdApi.MessageDocument -> caption?.text
    is TdApi.MessagePhoto -> caption?.text
    is TdApi.MessageVideo -> caption?.text
    else -> null
}
