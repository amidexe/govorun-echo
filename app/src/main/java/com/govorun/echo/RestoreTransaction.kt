package com.govorun.echo

import org.json.JSONObject
import com.govorun.echo.UserFiles.syncDirectory
import java.io.File
import java.util.UUID

/** Per-unit durable rename journals. One damaged journal never makes the rest of the library inaccessible. */
internal object RestoreTransaction {
    @Volatile var problem: String? = null
        private set
    @Synchronized
    fun recover(root:File,work:File) {
        work.listFiles().orEmpty().filter { it.isDirectory }.forEach { tx ->
            runCatching { recoverOne(root,tx) }.onFailure { problem="restore_recovery_failed" }
        }
    }
    private fun recoverOne(root:File,tx:File) {
        val journal=File(tx,"journal.json");val previous=File(tx,"journal.copy");val old=File(tx,"old")
        if(!journal.exists() && !previous.exists()) {
            if(old.exists()) {problem="restore_journal_damaged";return}
            tx.deleteRecursively();return
        }
        val value=listOf(journal,previous).firstNotNullOfOrNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        if(value==null) {problem="restore_journal_damaged";return}
        val rel=value.getString("target");require(PortablePath.safe(rel))
        val target=File(root,rel);require(target.canonicalPath.startsWith(root.canonicalPath+File.separator))
        if(!target.exists() && old.exists()) {target.parentFile?.mkdirs();check(old.renameTo(target)) {"rollback_failed"}}
        check(target.exists()) {"recovery_target_missing"}
        tx.deleteRecursively()
    }
    fun prepare(source:File) {
        if(source.isFile) java.io.RandomAccessFile(source,"r").use{it.fd.sync()}
        else {
            source.walkTopDown().filter{it.isFile}.forEach{file->java.io.RandomAccessFile(file,"r").use{it.fd.sync()}}
            source.walkBottomUp().filter{it.isDirectory}.forEach(::syncDirectory)
        }
    }
    @Synchronized
    fun install(root:File,relative:String,source:File,work:File,afterOldMoved:()->Unit={}) {
        require(PortablePath.safe(relative));recover(root,work)
        val target=File(root,relative);require(target.canonicalPath.startsWith(root.canonicalPath+File.separator))
        target.parentFile?.mkdirs();val tx=File(work,UUID.randomUUID().toString()).apply{mkdirs()};val incoming=File(tx,"incoming")
        check(source.renameTo(incoming)) {"stage_move_failed"}
        if(!target.exists()) {check(incoming.renameTo(target)) {"install_failed"};syncDirectory(target.parentFile!!);tx.delete();return}
        val body=JSONObject().put("target",relative).toString().toByteArray()
        for(name in listOf("journal.json","journal.copy")) File(tx,name).outputStream().use { out -> out.write(body);out.fd.sync() }
        syncDirectory(tx)
        check(target.renameTo(File(tx,"old"))) {"old_move_failed"}
        syncDirectory(tx);syncDirectory(target.parentFile!!)
        afterOldMoved()
        check(incoming.renameTo(target)) {"install_failed"}
        syncDirectory(target.parentFile!!)
        recoverOne(root,tx)
    }
}
