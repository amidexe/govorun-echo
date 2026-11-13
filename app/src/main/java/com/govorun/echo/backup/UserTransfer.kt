package com.govorun.echo.backup

import com.govorun.echo.RestoreTransaction
import com.govorun.echo.UserFiles

import android.content.Context
import android.net.Uri
import com.govorun.echo.notes.NoteStore
import java.io.File
import java.util.UUID

/** One native archive format for transfer and recovery, independent of note fields and storage providers. */
internal object UserTransfer {
    data class Pending(val directory:File,val plan:ArchiveRestore.Plan)
    fun workspace(context:Context)=UserFiles.workspace(context)
    fun discard(pending:Pending)=UserFiles.discard(pending.directory)
    fun create(context:Context,uri:Uri):PortableArchive.Created {
        checkQuiet(context)
        val value=(context.contentResolver.openOutputStream(uri,"wt") ?: error("destination_unavailable")).use { out -> PortableArchive.write(context.filesDir,PortablePreferences.capture(context),out) }
        val digest=(context.contentResolver.openInputStream(uri) ?: error("destination_unreadable")).use(PortableArchive::sha)
        check(digest==value.sha256){"copy_readback_failed"}
        return value
    }
    fun prepare(context:Context,uri:Uri):Pending {
        checkQuiet(context)
        val work=workspace(context)
        try {
            val staged=(context.contentResolver.openInputStream(uri) ?: error("source_unavailable")).use { input -> PortableArchive.stage(input,File(work,"staged"),context.filesDir) }
            val plan=ArchiveRestore.plan(staged,context.filesDir)
            check(plan.units.isNotEmpty()) {"no_recoverable_data"}
            return Pending(work,plan)
        } catch(e:Exception) {UserFiles.discard(work);throw e}
    }
    fun apply(context:Context,pending:Pending,settings:Boolean):ArchiveRestore.Result {
        checkQuiet(context)
        val result=ArchiveRestore.apply(pending.plan,context.filesDir,File(context.noBackupFilesDir,"restore-transaction"),settings) { body -> synchronized(NoteStore) {body()} }
        if(settings && pending.plan.preferences!=null) {
            val saved=runCatching {PortablePreferences.restore(context,pending.plan.preferences,true)}
            if(saved.isFailure)return result.copy(problems=result.problems+"preferences_not_restored")
        }
        return result
    }
    private fun checkQuiet(context:Context) {
        check(NoteStore.list(context).none { it.state==NoteStore.STATE_RECORDING || it.appending || it.archiving || it.state==NoteStore.STATE_RECOGNIZING }) {"recording_or_processing"}
        check(com.govorun.echo.notes.CommandHistory.entries(context).none { it.result.phase in setOf("processing", "dispatching") }) { "command_processing" }
        // A future authoritative database must get a real SQLite snapshot adapter; silently omitting it is forbidden.
        UserFiles.requireSupportedDatabases(context)
    }
}
