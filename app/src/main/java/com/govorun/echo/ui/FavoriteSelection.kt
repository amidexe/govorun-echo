package com.govorun.echo.ui

import android.content.Context
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.notes.NoteFavorites
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Like read-state selection: write off the UI thread and leave failed notes selected. */
internal fun markSelectedFavorites(context: Context, scope: CoroutineScope, ids: List<String>, on: Boolean, reload: () -> Unit, remaining: (List<String>) -> Unit) {
    scope.launch {
        val failed = withContext(Dispatchers.IO) { NoteFavorites.setMany(NoteStore.root(context), ids, on) }
        reload()
        remaining(failed)
        val count = ids.distinct().size - failed.size
        val result = context.getString(if (on) R.string.notes_favorites_added else R.string.notes_favorites_removed, count)
        val text = if (failed.isEmpty()) result else context.getString(R.string.notes_favorites_partial, result, failed.size)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
