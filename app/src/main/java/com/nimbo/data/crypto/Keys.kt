package com.nimbo.data.crypto

import com.nimbo.core.tdlib.SecretStore
import com.nimbo.core.util.fmtDur
import com.nimbo.data.cloud.KeyText
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.kindOfName
import com.nimbo.data.cloud.parseMeta
import com.nimbo.data.cloud.tgMeta
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val InfoAad = "nimbo-info-v1"
private const val KeyAad = "nimbo-key-v1"

// Lo que va cifrado en el caption (#enc): nombre real, tamaño en claro y
// #tgmeta (título/artista/duración de audio).
data class EncInfo(val name: String, val size: Long, val meta: String)

fun sealInfo(key: ByteArray, i: EncInfo): String =
    sealText(key, "${i.name}\n${i.size}\n${i.meta}".toByteArray(), InfoAad)

fun openInfo(key: ByteArray, s: String): EncInfo? {
    val v = openText(key, s, InfoAad)?.decodeToString()?.split('\n', limit = 3) ?: return null
    if (v.size != 3) return null
    return EncInfo(v[0], v[1].toLongOrNull() ?: return null, v[2])
}

private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

// Maestra nueva envuelta con la contraseña.
fun wrapMaster(password: CharArray, master: ByteArray, iters: Int = KdfIters): KeyText {
    val salt = randomBytes(16)
    return KeyText(iters, b64(salt), sealText(deriveKek(password, salt, iters), master, KeyAad))
}

// null = contraseña incorrecta.
fun unwrapMaster(password: CharArray, k: KeyText): ByteArray? {
    val salt = runCatching { Base64.getUrlDecoder().decode(k.salt) }.getOrNull() ?: return null
    return openText(deriveKek(password, salt, k.iters), k.wrapped, KeyAad)
}

// Nombres reales de los cifrados. Sin clave (o info que no abre): quedan
// opacos y bloqueados.
fun openNames(files: List<TgFile>, key: ByteArray?): List<TgFile> = files.map { f ->
    if (f.encInfo.isEmpty()) return@map f
    val i = key?.let { openInfo(it, f.encInfo) } ?: return@map f.copy(locked = true, kind = TgKind.DOC)
    val kind = kindOfName(i.name)
    val (title, artist, dur) = if (kind == TgKind.AUDIO) parseMeta(i.meta) else Triple("", "", 0)
    f.copy(
        name = i.name, kind = kind, sizeBytes = i.size, locked = false,
        title = title, artist = artist, durSec = dur, dur = if (dur > 0) fmtDur(dur) else ""
    ).let { it.copy(meta = tgMeta(it)) }
}

// Clave maestra de este teléfono (SecretStore, cifrada por Keystore).
// null = sin cifrado o bloqueado.
@Singleton
class KeyRing(private val load: () -> ByteArray?, private val save: (ByteArray?) -> Unit) {
    @Inject constructor(secrets: SecretStore) : this(secrets::encKey, secrets::setEncKey)

    private val _key by lazy { MutableStateFlow(load()) }
    val key: StateFlow<ByteArray?> get() = _key

    fun set(k: ByteArray?) {
        save(k)
        _key.value = k
    }
}
