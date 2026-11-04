package com.govorun.echo.notes

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Content changed on disk; consumers reread the store, never send a stale UI list. No note text enters this signal. */
internal object NoteChanges {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun signal() { events.tryEmit(Unit) }
}
