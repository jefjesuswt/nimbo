package com.nimbo.ui.telegram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Índice local de la nube. Fuente verdad = captions (TDLib); esto solo
// acelera carpetas/búsqueda/offline. Sin Room: una tabla, cero deps.
class TgIndex(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "tg_index", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tg_files(" +
                "message_id INTEGER PRIMARY KEY, file_id INTEGER, name TEXT, " +
                "kind TEXT, size INTEGER, date INTEGER, folder TEXT, " +
                "title TEXT, artist TEXT, thumb_file INTEGER, cover_hash TEXT, " +
                "dur_sec INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folder ON tg_files(folder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_name ON tg_files(name)")
        // Aparte de tg_files: upsert hace REPLACE y onUpgrade la tira (se
        // reconstruye de la nube); las posiciones no se pueden reconstruir.
        db.execSQL("CREATE TABLE IF NOT EXISTS positions(message_id INTEGER PRIMARY KEY, pos_ms INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS tg_files")
        onCreate(db)
    }

    // Continuar donde lo dejaste (spec 9a).
    fun savePos(messageId: Long, ms: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO positions(message_id, pos_ms) VALUES(?, ?)",
            arrayOf<Any>(messageId, ms)
        )
    }

    fun positions(): Map<Long, Long> {
        val out = mutableMapOf<Long, Long>()
        readableDatabase.rawQuery("SELECT message_id, pos_ms FROM positions WHERE pos_ms > 0", null).use {
            while (it.moveToNext()) out[it.getLong(0)] = it.getLong(1)
        }
        return out
    }

    fun pos(messageId: Long): Long =
        readableDatabase.rawQuery("SELECT pos_ms FROM positions WHERE message_id=?", arrayOf(messageId.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun upsert(fs: List<TgFile>) {
        if (fs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            fs.forEach { f ->
                val v = ContentValues().apply {
                    put("message_id", f.messageId)
                    put("file_id", f.fileId)
                    put("name", f.name)
                    put("kind", f.kind.name)
                    put("size", f.sizeBytes)
                    put("date", f.dateSec)
                    put("folder", f.folder)
                    put("title", f.title)
                    put("artist", f.artist)
                    put("thumb_file", f.thumbFileId)
                    put("cover_hash", f.coverHash)
                    put("dur_sec", f.durSec)
                }
                db.replace("tg_files", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun all(): List<TgFile> {
        val out = mutableListOf<TgFile>()
        readableDatabase.query(
            "tg_files", null, null, null, null, null, "date DESC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("message_id")
            val iF = c.getColumnIndexOrThrow("file_id")
            val iN = c.getColumnIndexOrThrow("name")
            val iK = c.getColumnIndexOrThrow("kind")
            val iS = c.getColumnIndexOrThrow("size")
            val iD = c.getColumnIndexOrThrow("date")
            val iFo = c.getColumnIndexOrThrow("folder")
            val iT = c.getColumnIndexOrThrow("title")
            val iA = c.getColumnIndexOrThrow("artist")
            val iTh = c.getColumnIndexOrThrow("thumb_file")
            val iCh = c.getColumnIndexOrThrow("cover_hash")
            val iDu = c.getColumnIndexOrThrow("dur_sec")
            while (c.moveToNext()) {
                val kind = runCatching { TgKind.valueOf(c.getString(iK)) }
                    .getOrDefault(TgKind.DOC)
                out.add(
                    TgFile(
                        id = c.getLong(iId).toString(),
                        name = c.getString(iN) ?: "file",
                        kind = kind,
                        meta = "",
                        sizeBytes = c.getLong(iS),
                        messageId = c.getLong(iId),
                        fileId = c.getInt(iF),
                        folder = c.getString(iFo) ?: "",
                        dateSec = c.getInt(iD),
                        title = c.getString(iT) ?: "",
                        artist = c.getString(iA) ?: "",
                        thumbFileId = c.getInt(iTh),
                        coverHash = c.getString(iCh) ?: "",
                        durSec = c.getInt(iDu)
                    )
                )
            }
        }
        // meta se reconstruye (display puro, no se guarda).
        return out.map { it.copy(meta = tgMeta(it)) }
    }

    fun setCover(messageId: Long, hash: String) {
        val v = ContentValues().apply { put("cover_hash", hash) }
        writableDatabase.update("tg_files", v, "message_id=?", arrayOf(messageId.toString()))
    }

    fun delete(messageId: Long) {
        writableDatabase.delete("tg_files", "message_id=?", arrayOf(messageId.toString()))
    }

    // Hashes conocidos (para no perder covers en cada refresh).
    fun hashes(): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        readableDatabase.query(
            "tg_files", arrayOf("message_id", "cover_hash"),
            "cover_hash IS NOT NULL AND cover_hash != ''", null, null, null, null
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("message_id")
            val iH = c.getColumnIndexOrThrow("cover_hash")
            while (c.moveToNext()) out[c.getLong(iId)] = c.getString(iH) ?: ""
        }
        return out
    }

    fun clear() {
        writableDatabase.delete("tg_files", null, null)
    }
}

// Display desde campos (misma forma que mapMessage).
fun tgMeta(f: TgFile): String {
    val size = formatBytes(f.sizeBytes)
    val date = if (f.dateSec > 0) fmtDate(f.dateSec) else ""
    return listOfNotNull(
        f.artist.ifEmpty { null },
        f.dur.ifEmpty { null },
        size,
        date.ifEmpty { null }
    ).joinToString(" · ")
}
