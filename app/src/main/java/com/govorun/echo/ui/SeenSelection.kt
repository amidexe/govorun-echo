package com.govorun.echo.ui

import android.content.Context
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.notes.NoteSeen
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep failures selected for retry; a large selection never blocks the UI thread. */
internal fun markSelectedSeen(context: Context, scope: CoroutineScope, ids: List<String>, unseen: Boolean, reload: () -> Unit, remaining: (List<String>) -> Unit) {
    scope.launch {
        val failed = withContext(Dispatchers.IO) { NoteSeen.setMany(NoteStore.root(context), ids, unseen) }
        reload()
        remaining(failed)
        val count = ids.distinct().size - failed.size
        val text = if (failed.isEmpty()) context.getString(R.string.notes_marked, count) else context.getString(R.string.notes_marked_partial, count, failed.size)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
