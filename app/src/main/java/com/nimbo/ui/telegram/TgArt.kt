package com.nimbo.ui.telegram

// Arte embebido desde bytes parciales (head). MediaMetadataRetriever no lee
// truncados, así que se parsea a mano. Puro, testeable.
object TgArt {

    fun picture(data: ByteArray): ByteArray? =
        id3Apic(data) ?: flacPicture(data) ?: mp4Covr(data)

    // ---- ID3v2 (mp3): header 10B + frames; APIC = encoding + mime\0 + tipo + desc\0 + img.
    fun id3Apic(data: ByteArray): ByteArray? {
        if (data.size < 10 || data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return null
        val ver = data[3].toInt()
        if (ver !in 2..4) return null
        val flags = data[5].toInt()
        var tagSize = syncSafe(data, 6)
        if (tagSize <= 0 || tagSize > data.size) tagSize = data.size - 10
        var tag = data.copyOfRange(10, 10 + tagSize)
        // Unsynchronisation: FF 00 → FF.
        if (flags and 0x80 != 0) {
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < tag.size) {
                out.add(tag[i])
                if (tag[i] == 0xFF.toByte() && i + 1 < tag.size && tag[i + 1] == 0.toByte()) i++
                i++
            }
            tag = out.toByteArray()
        }
        var p = 0
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val size = if (ver == 4) syncSafe(tag, p + 4) else int32(tag, p + 4)
            if (size <= 0 || p + 10 + size > tag.size) break
            if (id == "APIC") {
                val body = tag.copyOfRange(p + 10, p + 10 + size)
                return apicImage(body)
            }
            if (id[0] == 0.toChar()) break // padding
            p += 10 + size
        }
        return null
    }

    private fun apicImage(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        var p = 1 // text encoding
        val enc = body[0].toInt()
        // MIME hasta \0 (siempre latin1).
        val mimeEnd = body.indexOf(0.toByte(), p)
        if (mimeEnd < 0) return null
        p = mimeEnd + 1 + 1 // + tipo picture
        if (p >= body.size) return null
        // Descripción según encoding.
        p = if (enc == 0 || enc == 3) {
            val e = body.indexOf(0.toByte(), p)
            if (e < 0) return null
            e + 1
        } else {
            var i = p
            while (i + 1 < body.size && !(body[i] == 0.toByte() && body[i + 1] == 0.toByte())) i += 2
            if (i + 1 >= body.size) return null
            i + 2
        }
        if (p >= body.size) return null
        return body.copyOfRange(p, body.size)
    }

    // ---- FLAC: "fLaC" + bloques (1B header + 3B len); tipo 6 = PICTURE.
    fun flacPicture(data: ByteArray): ByteArray? {
        if (data.size < 4 || !(data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() && data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte())) return null
        var p = 4
        while (p + 4 <= data.size) {
            val last = data[p].toInt() and 0x80 != 0
            val type = data[p].toInt() and 0x7F
            val len = ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            if (p + 4 + len > data.size) return null
            if (type == 6) {
                var q = p + 4
                fun u32(): Int {
                    val v = int32(data, q)
                    q += 4
                    return v
                }
                val mimeLen = u32()
                q += mimeLen
                val descLen = u32()
                q += descLen + 4 + 4 + 4 + 4 // w,h,depth,colors
                val imgLen = u32()
                if (imgLen <= 0 || q + imgLen > data.size) return null
                return data.copyOfRange(q, q + imgLen)
            }
            p += 4 + len
            if (last) break
        }
        return null
    }

    // ---- MP4/M4A: átomos anidados; covr>data salta 8B (tipo+locale).
    // Solo sirve si moov está en el head (faststart); si no, null.
    fun mp4Covr(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        return findCovr(data, 0, data.size)
    }

    private fun findCovr(data: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        while (p + 8 <= end) {
            val size = int32(data, p)
            if (size < 8) return null
            val name = String(data, p + 4, 4, Charsets.ISO_8859_1)
            val bodyStart = p + 8
            val bodyEnd = (p + size).coerceAtMost(end)
            if (name == "covr") {
                var q = bodyStart
                while (q + 8 <= bodyEnd) {
                    val ds = int32(data, q)
                    if (ds < 16 || q + ds > bodyEnd) break
                    // data: 4 size + 4 "data" + 4 tipo + 4 locale + imagen.
                    if (String(data, q + 4, 4, Charsets.ISO_8859_1) == "data") {
                        return data.copyOfRange(q + 16, q + ds)
                    }
                    q += ds
                }
                return null
            }
            if (name in setOf("moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "stbl")) {
                // meta tiene 4B de flags/version antes de sus hijos.
                val inner = if (name == "meta") bodyStart + 4 else bodyStart
                findCovr(data, inner, bodyEnd)?.let { return it }
            }
            if (size <= 0) break
            p += size
        }
        return null
    }

    private fun syncSafe(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0x7F) shl 21) or ((b[p + 1].toInt() and 0x7F) shl 14) or
            ((b[p + 2].toInt() and 0x7F) shl 7) or (b[p + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    private fun ByteArray.indexOf(v: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == v) return i
        return -1
    }
}
