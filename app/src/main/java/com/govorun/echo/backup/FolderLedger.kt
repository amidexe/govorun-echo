package com.govorun.echo.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Device-local proof of files published by Echo, including after a note leaves the trash. No note bodies. */
internal class FolderLedger(context: Context, private val tree: String) : Closeable {
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "folder-exports.db"), null)
    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS files (tree TEXT NOT NULL, name TEXT NOT NULL, digest TEXT, pending TEXT, stamp TEXT, PRIMARY KEY(tree,name))")
    }

    data class Entry(val digest: String?, val pending: String?, val stamp: String?) {
        fun owns(actual: String) = actual == digest || actual == pending
    }

    fun entries(): MutableMap<String, Entry> {
        val rows = mutableMapOf<String, Entry>()
        db.rawQuery("SELECT name,digest,pending,stamp FROM files WHERE tree=?", arrayOf(tree)).use { cursor ->
            while (cursor.moveToNext()) rows[cursor.getString(0)] = Entry(cursor.getString(1), cursor.getString(2), cursor.getString(3))
        }
        return rows
    }

    fun put(name: String, entry: Entry) {
        val row = ContentValues().apply {
            put("tree", tree); put("name", name); put("digest", entry.digest); put("pending", entry.pending); put("stamp", entry.stamp)
        }
        check(db.insertWithOnConflict("files", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "receipt_failed" }
    }

    fun forget(name: String) { db.delete("files", "tree=? AND name=?", arrayOf(tree, name)) }
    override fun close() = db.close()
}
