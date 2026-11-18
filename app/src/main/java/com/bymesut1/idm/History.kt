package com.bymesut1.idm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HRow(val id: Long, val url: String, val title: String, val ts: Long)

/** Tarayıcı geçmişi (kalıcı, SQLite). */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE h (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX h_ts ON h(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(url: String, title: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var lastId = -1L
        var lastUrl = ""
        val c = db.rawQuery("SELECT id, url FROM h ORDER BY id DESC LIMIT 1", null)
        try {
            if (c.moveToFirst()) {
                lastId = c.getLong(0)
                lastUrl = c.getString(1)
            }
        } finally {
            c.close()
        }
        val cv = ContentValues()
        cv.put("ts", now)
        if (title.isNotEmpty()) cv.put("title", title)
        if (lastUrl == url) {
            db.update("h", cv, "id=?", arrayOf(lastId.toString()))
        } else {
            cv.put("url", url)
            db.insert("h", null, cv)
        }
    }

    @Synchronized
    fun setTitle(url: String, title: String) {
        writableDatabase.execSQL(
            "UPDATE h SET title=? WHERE id=(SELECT id FROM h WHERE url=? ORDER BY id DESC LIMIT 1)",
            arrayOf<Any>(title, url)
        )
    }

    @Synchronized
    fun list(q: String, limit: Int = 400): List<HRow> {
        val out = ArrayList<HRow>()
        val like = "%$q%"
        val c = readableDatabase.rawQuery(
            "SELECT id, url, title, ts FROM h WHERE url LIKE ? OR title LIKE ? ORDER BY ts DESC LIMIT $limit",
            arrayOf(like, like)
        )
        try {
            while (c.moveToNext()) {
                out.add(HRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3)))
            }
        } finally {
            c.close()
        }
        return out
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("h", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("h", null, null)
    }
}
