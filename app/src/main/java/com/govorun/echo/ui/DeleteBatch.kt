package com.govorun.echo.ui

import androidx.compose.material3.SnackbarHostState
import com.govorun.echo.notes.NoteTrash
import java.io.File

/**
 * Deleting is moving to the trash. A series of quick deletes shares one "Undo" that brings all of
 * them back; what is not undone simply stays in the trash until it expires.
 */
internal class DeleteBatch {
    private val ids = mutableListOf<String>()
    private var token = 0

    /** Navigation must not dismiss the undo for notes moved to trash. */
    val hasPendingUndo: Boolean
        get() = ids.isNotEmpty()

    suspend fun deleted(
        moved: List<String>,
        root: File,
        trash: File,
        snackbar: SnackbarHostState,
        message: (Int) -> String,
        undo: String,
        reload: () -> Unit,
    ) {
        ids += moved
        val mine = ++token
        if (snackbar.showUndo(message(ids.size), undo)) {
            NoteTrash.restore(root, trash, ids.toList())
            ids.clear()
            reload()
        } else if (mine == token) {
            ids.clear()
        }
    }
}
