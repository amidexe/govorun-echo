package com.govorun.echo.record

import android.content.Context
import com.govorun.echo.backup.BackupFolder
import com.govorun.echo.backup.Snapshot
import com.govorun.echo.commands.Dispatcher
import com.govorun.echo.notes.EmptyNotes

/** A note's text has come: it goes to the copy folder and to the commands — or, empty, to the trash and nowhere else. */
internal fun afterNoteDone(
    context: Context,
    noteId: String,
) {
    if (!EmptyNotes.dropIfEmpty(context, noteId)) {
        Dispatcher.onNoteDoneAsync(context, noteId) {
            BackupFolder.syncAsync(context)
            com.govorun.echo.smart.itogiByItself(context, noteId)
        }
    }
    Snapshot.write(context)
}
