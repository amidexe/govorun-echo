package com.govorun.echo

import android.content.Context
import java.io.File
import java.util.UUID

/** Durable user files and private transient restore work have different roots. */
internal object UserFiles {
    fun requireSupportedDatabases(context: Context) {
        // An authoritative SQLite store needs a consistent snapshot adapter before transfer.
        check(context.databaseList().filterNot {
            it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal")
        }.all { it == "index.db" }) { "unsupported_user_database" }
    }
    private val active=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun notes(context:Context):File {
        RestoreTransaction.recover(context.filesDir,File(context.noBackupFilesDir,"restore-transaction"))
        context.noBackupFilesDir.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("transfer-[0-9a-f-]{36}")) && it.absolutePath !in active}.forEach{it.deleteRecursively()}
        return File(context.filesDir,"notes").apply{mkdirs()}
    }
    fun workspace(context:Context)=File(context.noBackupFilesDir,"transfer-${UUID.randomUUID()}").apply{active.add(absolutePath);mkdirs()}
    fun discard(directory:File){directory.deleteRecursively();active.remove(directory.absolutePath)}
}
