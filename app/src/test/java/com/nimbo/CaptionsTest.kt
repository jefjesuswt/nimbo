package com.nimbo

import com.nimbo.core.util.fmtDate
import com.nimbo.core.util.fmtDur
import com.nimbo.data.cloud.CloudTab
import com.nimbo.data.cloud.KindFilter
import com.nimbo.data.cloud.SortKey
import com.nimbo.data.cloud.TgFile
import com.nimbo.data.cloud.TgGenTag
import com.nimbo.data.cloud.TgKind
import com.nimbo.data.cloud.applyCoverLinks
import com.nimbo.data.cloud.childrenOf
import com.nimbo.data.cloud.countRecursive
import com.nimbo.data.cloud.coverLinkOf
import com.nimbo.data.cloud.decodeText
import com.nimbo.data.cloud.defaultSort
import com.nimbo.data.cloud.filterTgFiles
import com.nimbo.data.cloud.isCoverName
import com.nimbo.data.cloud.isFolderCover
import com.nimbo.data.cloud.isGenCover
import com.nimbo.data.cloud.isKeepMark
import com.nimbo.data.cloud.mapMessage
import com.nimbo.data.cloud.normalizePath
import com.nimbo.data.cloud.parentOf
import com.nimbo.data.cloud.parseCoverLink
import com.nimbo.data.cloud.parseCoverName
import com.nimbo.data.cloud.parseFolder
import com.nimbo.data.cloud.parseMeta
import com.nimbo.data.cloud.showTitle
import com.nimbo.data.cloud.sortTg
import com.nimbo.data.cloud.tagCoverLink
import com.nimbo.data.cloud.tagCoverName
import com.nimbo.data.cloud.tagFolder
import com.nimbo.data.cloud.tagMeta
import com.nimbo.data.media.coverHashOf
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionsTest {
    // Fechas y meta deterministas, sea cual sea el idioma de la máquina.
    @org.junit.Before fun us() { java.util.Locale.setDefault(java.util.Locale.US) }

    private fun audioMsg(
        id: Long = 5,
        file: String = "mix.mp3",
        dur: Int = 3723,
        caption: String = "hola #nimbo/music/albums"
    ): TdApi.Message {
        val f = TdApi.File()
        f.id = 7
        f.size = 142L shl 20
        val a = TdApi.Audio()
        a.duration = dur
        a.title = ""
        a.performer = ""
        a.fileName = file
        a.mimeType = "audio/mpeg"
        a.audio = f
        val m = TdApi.Message()
        m.id = id
        m.date = 1728691200 // 2024-10-12 UTC
        m.content = TdApi.MessageAudio(a, TdApi.FormattedText(caption, emptyArray()))
        return m
    }

    @Test fun mapAudio() {
        val f = mapMessage(audioMsg())!!
        assertEquals("5", f.id)
        assertEquals("mix.mp3", f.name)
        assertEquals(TgKind.AUDIO, f.kind)
        assertEquals(5L, f.messageId)
        assertEquals(7, f.fileId)
        assertEquals("music/albums", f.folder)
        assertEquals("1:02:03 · 142.0 MB · 12 Oct", f.meta)
    }

    @Test fun mapDocKinds() {
        fun doc(name: String): TdApi.Message {
            val f = TdApi.File()
            f.size = 10
            val d = TdApi.Document()
            d.fileName = name
            d.mimeType = ""
            d.document = f
            val m = TdApi.Message()
            m.id = 9
            m.date = 1728691200
            m.content = TdApi.MessageDocument(d, TdApi.FormattedText("", emptyArray()))
            return m
        }
        assertEquals(TgKind.ARCHIVE, mapMessage(doc("a.7z"))!!.kind)
        assertEquals(TgKind.DOC, mapMessage(doc("a.pdf"))!!.kind)
        assertEquals(TgKind.AUDIO, mapMessage(doc("a.ogg"))!!.kind)
    }

    @Test fun mapSkipsOther() {
        val m = TdApi.Message()
        m.id = 1
        m.content = TdApi.MessageText().apply {
            text = TdApi.FormattedText("hola", emptyArray())
        }
        assertNull(mapMessage(m))
    }

    @Test fun mapPhotoVideo() {
        val f = TdApi.File()
        f.id = 21
        f.size = 300000
        val sz = TdApi.PhotoSize()
        sz.type = "y"
        sz.photo = f
        sz.width = 1280
        sz.height = 720
        val pm = TdApi.Message()
        pm.id = 11
        pm.date = 1728691200
        pm.content = TdApi.MessagePhoto(
            TdApi.Photo().apply { sizes = arrayOf(sz) },
            TdApi.FormattedText("#nimbo/pics", emptyArray()), false, false, false
        )
        val p = mapMessage(pm)!!
        assertEquals(TgKind.IMAGE, p.kind)
        assertEquals(21, p.fileId)
        assertEquals(21, p.thumbFileId)
        assertEquals("pics", p.folder)

        val vf = TdApi.File()
        vf.id = 22
        vf.size = 5000000
        val v = TdApi.Video()
        v.duration = 65
        v.fileName = "clip.mp4"
        v.mimeType = "video/mp4"
        v.video = vf
        val vm = TdApi.Message()
        vm.id = 12
        vm.date = 1728691200
        vm.content = TdApi.MessageVideo(
            v, emptyArray(), emptyArray(), null, 0,
            TdApi.FormattedText("", emptyArray()), false, false, false
        )
        val vv = mapMessage(vm)!!
        assertEquals(TgKind.VIDEO, vv.kind)
        assertEquals("clip.mp4", vv.name)
        assertEquals("1:05", vv.dur)
    }

    @Test fun mapAudioMetadata() {
        val f = TdApi.File()
        f.id = 30
        f.size = 1000
        val th = TdApi.File()
        th.id = 31
        val a = TdApi.Audio()
        a.duration = 200
        a.title = "Night Drive"
        a.performer = "Some Artist"
        a.fileName = "track01.mp3"
        a.mimeType = "audio/mpeg"
        a.audio = f
        a.albumCoverThumbnail = TdApi.Thumbnail().apply { file = th }
        val m = TdApi.Message()
        m.id = 13
        m.date = 1728691200
        m.content = TdApi.MessageAudio(a, TdApi.FormattedText("", emptyArray()))
        val t = mapMessage(m)!!
        assertEquals("Night Drive", t.title)
        assertEquals("Some Artist", t.artist)
        assertEquals(31, t.thumbFileId)
        assertEquals("Night Drive", t.showTitle())
        assertEquals("Some Artist · 3:20 · 1000 B · 12 Oct", t.meta)
    }

    @Test fun mediaFilter() {
        val items = listOf(
            TgFile("a", "x.mp3", TgKind.AUDIO, ""),
            TgFile("b", "y.jpg", TgKind.IMAGE, ""),
            TgFile("c", "z.pdf", TgKind.DOC, "")
        )
        assertEquals(listOf("b"), filterTgFiles(items, KindFilter.MEDIA).map { it.id })
        assertEquals(listOf("c"), filterTgFiles(items, KindFilter.OTHER).map { it.id })
        assertEquals(
            "x.mp3",
            TgFile("a", "x.mp3", TgKind.AUDIO, "").showTitle()
        )
    }

    @Test fun folders() {
        assertEquals("", parseFolder("sin tag"))
        assertEquals("music/albums", parseFolder("mix #nimbo/music/albums/"))
        assertEquals("a/b", parseFolder("#nimbo//a//b"))
        // Subidas de la época Toolbox se siguen leyendo y migran al retag.
        assertEquals("music/albums", parseFolder("mix #toolbox/music/albums"))
        assertEquals("mix #nimbo/a/b", tagFolder("mix #toolbox/old", "a/b"))
        assertEquals("music", normalizePath(" music "))
        assertEquals("#nimbo/a", tagFolder("", "a"))
        assertEquals("mix #nimbo/a/b", tagFolder("mix #nimbo/old", "a/b"))
        assertEquals("mix", tagFolder("mix #nimbo/old", ""))
    }

    @Test fun captionMeta() {
        val c = tagMeta("#nimbo/music", "Night Drive", "Some Artist", 3723)
        assertEquals("#nimbo/music #tgmeta 3723s \"Night Drive\" \"Some Artist\"", c)
        val (t, a, d) = parseMeta(c)
        assertEquals("Night Drive", t)
        assertEquals("Some Artist", a)
        assertEquals(3723, d)
        assertEquals(Triple("", "", 0), parseMeta("sin meta"))
        // Re-etiquetar no duplica.
        assertEquals(c, tagMeta(c, "X", "Y", 1).let { tagMeta(it, "Night Drive", "Some Artist", 3723) })
    }

    @Test fun fmt() {
        assertEquals("48:22", fmtDur(2902))
        assertEquals("1:02:03", fmtDur(3723))
        assertEquals("12 Oct", fmtDate(1728691200))
        assertEquals("1 Jan", fmtDate(0))
    }

    @Test fun coverLink() {
        assertEquals(true, isCoverName("folder.JPG"))
        assertEquals(true, isCoverName("Cover.png"))
        assertEquals(false, isCoverName("song.mp3"))
        assertEquals(false, isCoverName("booklet-01.jpg"))
        val c = tagCoverName(tagCoverLink("#nimbo/music"), "cover.jpg")
        assertEquals("#nimbo/music #tgcover * #tgname \"cover.jpg\"", c)
        assertEquals("*", parseCoverLink(c))
        assertEquals("", parseCoverLink("sin tag"))
        assertEquals(true, isFolderCover(c))
        assertEquals(true, isFolderCover("#nimbo/m #tgcover * $TgGenTag"))
        // Links viejos no canónicos (por pista o imagen suelta): ignorados.
        assertEquals(false, isFolderCover("#nimbo/m #tgcover song.mp3"))
        assertEquals(false, isFolderCover(tagCoverName("#nimbo/m #tgcover *", "scan-02.png")))
        val f = TdApi.File()
        f.id = 77
        val sz = TdApi.PhotoSize()
        sz.photo = f
        sz.width = 500
        sz.height = 500
        fun photo(id: Long, cap: String) = TdApi.Message().apply {
            this.id = id
            content = TdApi.MessagePhoto(
                TdApi.Photo().apply { sizes = arrayOf(sz) },
                TdApi.FormattedText(cap, emptyArray()), false, false, false
            )
        }
        // Cover de archivo real: linkea a su carpeta y se lista con su nombre.
        assertEquals("music" to 77, coverLinkOf(photo(20, c)))
        assertEquals("cover.jpg", mapMessage(photo(20, c))?.name)
        // Link viejo por pista: sin link, queda como imagen normal.
        assertNull(coverLinkOf(photo(21, "#nimbo/music #tgcover song.mp3")))
        assertEquals("photo-21.jpg", mapMessage(photo(21, "#nimbo/music #tgcover song.mp3"))?.name)
        // Cover generada (#tggen): linkea pero oculta.
        val gen = "${tagCoverLink("#nimbo/music")} $TgGenTag"
        assertEquals("music" to 77, coverLinkOf(photo(22, gen)))
        assertNull(mapMessage(photo(22, gen)))
        // Cover como documento (bytes originales): linkea + visible.
        val df = TdApi.File()
        df.id = 78
        val dd = TdApi.Document()
        dd.document = df
        dd.fileName = "front.jpg"
        val md = TdApi.Message()
        md.id = 23
        md.content = TdApi.MessageDocument(
            dd,
            TdApi.FormattedText(tagCoverName(tagCoverLink("#nimbo/music"), "front.jpg"), emptyArray())
        )
        assertEquals("music" to 78, coverLinkOf(md))
        assertEquals("front.jpg", mapMessage(md)?.name)
        assertEquals(TgKind.IMAGE, mapMessage(md)?.kind)
    }

    @Test fun coverOnlyOwnFolder() {
        val audios = listOf(
            TgFile("a", "song.mp3", TgKind.AUDIO, "", folder = "music"),
            TgFile("b", "deep.mp3", TgKind.AUDIO, "", folder = "music/cd2"),
            TgFile("c", "own.mp3", TgKind.AUDIO, "", folder = "music", thumbFileId = 5)
        )
        val linked = applyCoverLinks(audios, mapOf("music" to 77))
        assertEquals(77, linked[0].thumbFileId)
        // Subcarpeta no hereda; arte embebido manda sobre la cover de carpeta.
        assertEquals(0, linked[1].thumbFileId)
        assertEquals(5, linked[2].thumbFileId)
    }

    @Test fun foldersNav() {
        val items = listOf(
            TgFile("a", "r.mp3", TgKind.AUDIO, "", folder = ""),
            TgFile("b", "x.mp3", TgKind.AUDIO, "", folder = "music"),
            TgFile("c", "y.mp3", TgKind.AUDIO, "", folder = "music/rock"),
            TgFile("d", "z.mp3", TgKind.AUDIO, "", folder = "music/rock/deep")
        )
        val (subs0, own0) = childrenOf(items, "")
        assertEquals(listOf("music"), subs0)
        assertEquals(listOf("a"), own0.map { it.id })
        val (subs1, own1) = childrenOf(items, "music")
        assertEquals(listOf("rock"), subs1)
        assertEquals(listOf("b"), own1.map { it.id })
        val (subs2, own2) = childrenOf(items, "music/rock")
        assertEquals(listOf("deep"), subs2)
        assertEquals(listOf("c"), own2.map { it.id })
        assertEquals("music", parentOf("music/rock"))
        assertEquals("", parentOf("music"))
        assertEquals(4, countRecursive(items, ""))
        assertEquals(3, countRecursive(items, "music"))
        assertEquals(1, countRecursive(items, "music/rock/deep"))
        // Carpetas vacías (marcadores) también navegan.
        val (subsK, ownK) = childrenOf(items, "", setOf("void", "music/empty"))
        assertEquals(listOf("music", "void"), subsK)
        assertEquals(listOf("a"), ownK.map { it.id })
        val (subsK2, _) = childrenOf(items, "music", setOf("music/empty", "music/rock"))
        assertEquals(listOf("empty", "rock"), subsK2)
    }

    @Test fun coverKinds() {
        assertEquals(true, isKeepMark("#nimbo/a/b #tgkeep"))
        assertEquals(false, isKeepMark("#nimbo/a/b"))
        assertEquals(true, isGenCover("#tgcover * #tggen"))
        assertEquals(false, isGenCover("#tgcover *"))
        val c = tagCoverName("#nimbo/m #tgcover *", "front.jpg")
        assertEquals("front.jpg", parseCoverName(c))
        assertEquals("*", parseCoverLink(c))
        assertEquals("m", parseFolder(c))
    }

    @Test fun search() {
        val a = TgFile("a", "Canción Triste.mp3", TgKind.AUDIO, "", folder = "music/rock", artist = "Some Artist")
        val b = TgFile("b", "notes.txt", TgKind.DOC, "", folder = "docs")
        val keyed = listOf(a, b).map { it to com.nimbo.data.cloud.searchKey(it) }
        fun q(s: String) = com.nimbo.data.cloud.searchIn(keyed, com.nimbo.data.cloud.searchTerms(s)).map { it.id }
        // Sin acentos ni mayúsculas, por artista, varias palabras = AND.
        assertEquals(listOf("a"), q("CANCION"))
        assertEquals(listOf("a"), q("artist"))
        assertEquals(listOf("a"), q("triste some"))
        assertEquals(emptyList<String>(), q("triste notes"))
        assertEquals(emptyList<String>(), q("   "))
        assertEquals(
            setOf("docs", "music", "music/rock", "void"),
            com.nimbo.data.cloud.allFolders(listOf(a, b), setOf("void"))
        )
    }

    @Test fun albums() {
        fun au(id: String, folder: String, artist: String, dur: Int) = TgFile(
            id, "$id.mp3", TgKind.AUDIO, "", 1L shl 20, folder = folder, artist = artist, durSec = dur, dateSec = 1728691200
        )
        val files = listOf(
            au("a", "music/rock/b-side", "Some Artist", 60),
            au("b", "music/rock/b-side", "Some Artist", 120),
            au("c", "music/rock/b-side", "Guest", 30),
            au("d", "", "", 0)
        )
        val albums = com.nimbo.data.cloud.albumsOf(files)
        assertEquals(listOf("", "music/rock/b-side"), albums.map { it.folder })
        val a = albums[1]
        assertEquals("b-side", a.name)
        assertEquals("", albums[0].name)
        assertEquals("Some Artist", com.nimbo.data.cloud.albumArtist(a))
        assertEquals("Some Artist · music/rock", com.nimbo.data.cloud.albumSubtitle(a, true))
        assertEquals("3:30 · 3.0 MB · 12 Oct", com.nimbo.data.cloud.albumInfo(a, true))
        assertEquals("1.0 MB · 12 Oct", com.nimbo.data.cloud.albumInfo(albums[0], true))
        // Subcarpeta con nombre repetido: el título suma el padre.
        val twins = com.nimbo.data.cloud.albumsOf(listOf(au("x", "Album A/CD1", "", 1), au("y", "Album B/CD1", "", 1), au("z", "Solo/Disc", "", 1)))
        assertEquals(listOf("Album A / CD1", "Album B / CD1", "Disc"), twins.map { it.name })
        val pic = TgFile("p", "p.jpg", TgKind.IMAGE, "", 1L shl 20, folder = "trip", dateSec = 1728691200)
        val vid = pic.copy(id = "v", kind = TgKind.VIDEO)
        assertEquals("2.0 MB · 12 Oct", com.nimbo.data.cloud.albumInfo(com.nimbo.data.cloud.TgAlbum("trip", listOf(pic, vid)), false))
    }

    @Test fun coverHash() {
        // SHA-256("abc") vector conocido.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            coverHashOf("abc".toByteArray())
        )
        assertEquals(64, coverHashOf(ByteArray(10)).length)
    }

    @Test fun decodeTextEncodings() {
        // UTF-8 con acentos y japonés.
        val utf8 = "café 日本語 ♪".toByteArray(Charsets.UTF_8)
        assertEquals("café 日本語 ♪" to "UTF-8", decodeText(utf8))
        // BOM UTF-8.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi" to "UTF-8", decodeText(bom))
        // Shift_JIS real (こんにちは no es UTF-8 válido).
        val sjis = "こんにちは".toByteArray(charset("Shift_JIS"))
        assertEquals("こんにちは" to "Shift_JIS", decodeText(sjis))
        // Latin-1 (café en 0xE9 suelto no pasa ni UTF-8 ni Shift_JIS).
        val latin = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        assertEquals("café" to "Latin-1", decodeText(latin))
        assertEquals("" to "UTF-8", decodeText(ByteArray(0)))
    }

    @Test fun sorting() {
        fun f(id: String, name: String, date: Int, size: Long, dur: Int) =
            TgFile(id, name, TgKind.AUDIO, "", size, dateSec = date, durSec = dur)
        val xs = listOf(f("a", "beta.mp3", 2, 30, 5), f("b", "Alpha.mp3", 3, 10, 9), f("c", "gamma.mp3", 1, 20, 1))
        fun ids(k: String) = sortTg(xs, SortKey.parse(k)!!).map { it.id }
        assertEquals(listOf("b", "a", "c"), ids("name:asc"))
        assertEquals(listOf("b", "a", "c"), ids("date:desc"))
        assertEquals(listOf("b", "c", "a"), ids("size:asc"))
        assertEquals(listOf("b", "a", "c"), ids("dur:desc"))
        assertEquals("name:asc", defaultSort(CloudTab.AUDIO).encode())
        assertEquals("date:desc", defaultSort(CloudTab.PHOTOS).encode())
        assertEquals(null, SortKey.parse("bogus"))
    }
}
