package com.nimbo.data.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Cifrado de archivos (spec 12), formato v1. Contrato: no romper.
//   cabecera (18 B) = "NIMBOENC" + versión (1) + log2(bloque) (1) + prefijo de nonce (8)
//   bloque i        = AES-256-GCM(clave maestra, nonce = prefijo + i (4 B, BE),
//                     aad = cabecera + [1 si es el último, 0 si no]) → ≤ bloque + 16 B
// Bloques independientes: seek sin descifrar todo. El índice en el nonce
// impide reordenar; el flag de último, truncar. Puras, testeables.
const val EncHeader = 18
const val EncTag = 16
const val EncBlockLog = 20 // 1 MiB
private val Magic = "NIMBOENC".toByteArray()
private const val Version: Byte = 1
private val rnd = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also(rnd::nextBytes)

fun newHeader(blockLog: Int = EncBlockLog): ByteArray =
    Magic + byteArrayOf(Version, blockLog.toByte()) + randomBytes(8)

// Tamaño de bloque en claro; IOException si no es un archivo nuestro.
fun blockOf(header: ByteArray): Int {
    if (header.size < EncHeader || !header.copyOf(8).contentEquals(Magic) || header[8] != Version) throw IOException("not nimbo")
    val log = header[9].toInt()
    if (log !in 4..24) throw IOException("bad block")
    return 1 shl log
}

// Archivo vacío = un bloque vacío (solo tag): también se autentica.
fun encBlocks(plain: Long, block: Int): Long = maxOf(1L, (plain + block - 1) / block)

fun cipherSize(plain: Long, block: Int): Long = EncHeader + plain + EncTag * encBlocks(plain, block)

fun plainSize(cipher: Long, block: Int): Long {
    val body = cipher - EncHeader
    val n = (body + block + EncTag - 1) / (block + EncTag)
    return (body - n * EncTag).coerceAtLeast(0L)
}

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(EncTag * 8, nonce))
        updateAAD(aad)
    }

private fun blockNonce(header: ByteArray, i: Long): ByteArray =
    header.copyOfRange(10, 18) + byteArrayOf((i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte())

private fun blockAad(header: ByteArray, last: Boolean): ByteArray =
    header.copyOf(EncHeader) + byteArrayOf(if (last) 1 else 0)

fun sealBlock(key: ByteArray, header: ByteArray, i: Long, last: Boolean, data: ByteArray, len: Int): ByteArray =
    gcm(Cipher.ENCRYPT_MODE, key, blockNonce(header, i), blockAad(header, last)).doFinal(data, 0, len)

// AEADBadTagException (IOException no) si la clave, el orden o el final no cuadran.
fun openBlock(key: ByteArray, header: ByteArray, i: Long, last: Boolean, sealed: ByteArray, len: Int): ByteArray =
    gcm(Cipher.DECRYPT_MODE, key, blockNonce(header, i), blockAad(header, last)).doFinal(sealed, 0, len)

fun skipFully(input: InputStream, n: Long) {
    val buf = ByteArray(64 * 1024)
    var left = n
    while (left > 0) {
        val s = input.skip(left)
        if (s > 0) {
            left -= s
            continue
        }
        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw IOException("short")
        left -= r
    }
}

private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
    var got = 0
    while (got < len) {
        val r = input.read(buf, got, len - got)
        if (r < 0) throw IOException("short")
        got += r
    }
}

// Escribe los bytes cifrados [from, from+len) leyendo el claro desde su
// inicio (salta hasta el primer bloque que toca). Sirve entero (from = 0)
// o por partes (spec 13). Si el claro no mide plainSize → IOException.
fun encryptRange(
    key: ByteArray, header: ByteArray, plain: InputStream, plainSize: Long,
    out: OutputStream, from: Long, len: Long
) {
    val block = blockOf(header)
    val seal = block + EncTag
    val end = from + minOf(len, cipherSize(plainSize, block) - from)
    var pos = from
    if (pos < EncHeader) {
        val e = minOf(end, EncHeader.toLong()).toInt()
        out.write(header, pos.toInt(), e - pos.toInt())
        pos = e.toLong()
    }
    if (pos >= end) return
    val n = encBlocks(plainSize, block)
    var i = (pos - EncHeader) / seal
    skipFully(plain, i * block)
    val buf = ByteArray(block)
    while (pos < end) {
        val want = minOf(block.toLong(), plainSize - i * block).toInt()
        readFully(plain, buf, want)
        val sealed = sealBlock(key, header, i, i == n - 1, buf, want)
        val start = EncHeader + i * seal
        val s = (pos - start).toInt()
        val e = minOf(end - start, sealed.size.toLong()).toInt()
        out.write(sealed, s, e - s)
        pos = start + e
        i++
        if (i == n && plain.read() != -1) throw IOException("size changed")
    }
}

// Recibe los bytes cifrados en orden (de una o varias partes) y escribe el
// claro en out. close() termina y verifica el final (no cierra out).
class DecryptingOutputStream(
    private val key: ByteArray,
    private val total: Long,
    private val out: OutputStream
) : OutputStream() {
    private val header = ByteArray(EncHeader)
    private var buf = ByteArray(0)
    private var seal = 0
    private var hlen = 0
    private var blen = 0
    private var index = 0L
    private var seen = 0L
    private var done = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        seen += len
        if (seen > total || done) throw IOException("too long")
        var o = off
        var n = len
        if (hlen < EncHeader) {
            val k = minOf(n, EncHeader - hlen)
            System.arraycopy(b, o, header, hlen, k)
            hlen += k
            o += k
            n -= k
            if (hlen < EncHeader) return
            seal = blockOf(header) + EncTag
            buf = ByteArray(seal)
        }
        while (n > 0) {
            val k = minOf(n, seal - blen)
            System.arraycopy(b, o, buf, blen, k)
            blen += k
            o += k
            n -= k
            if (blen == seal) flush(EncHeader + (index + 1) * seal == total)
        }
    }

    private fun flush(last: Boolean) {
        out.write(openBlock(key, header, index, last, buf, blen))
        index++
        blen = 0
        done = last
    }

    override fun close() {
        if (done) return
        if (hlen < EncHeader || blen == 0 || seen != total) throw IOException("truncated")
        flush(true)
    }
}

// Texto corto (nombre + metadatos) para el caption: base64url de
// nonce (12) + GCM. aad separa usos (nombre vs clave envuelta).
fun sealText(key: ByteArray, text: ByteArray, aad: String): String {
    val nonce = randomBytes(12)
    val c = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad.toByteArray()).doFinal(text)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + c)
}

fun openText(key: ByteArray, s: String, aad: String): ByteArray? = runCatching {
    val raw = Base64.getUrlDecoder().decode(s)
    gcm(Cipher.DECRYPT_MODE, key, raw.copyOf(12), aad.toByteArray()).doFinal(raw, 12, raw.size - 12)
}.getOrNull()

const val KdfIters = 600_000

// Contraseña → clave que envuelve la maestra (PBKDF2, en Android 8+ sin deps).
fun deriveKek(password: CharArray, salt: ByteArray, iters: Int): ByteArray {
    val spec = PBEKeySpec(password, salt, iters, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}
