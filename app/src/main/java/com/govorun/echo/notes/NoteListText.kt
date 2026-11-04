package com.govorun.echo.notes

/** Only genuine saved metadata can be a title. Untitled notes remain one source preview. */
internal data class NoteListText(val title: String, val preview: String)

internal fun NoteStore.Entry.listText(): NoteListText {
    val named = typedTitle().ifBlank { smartName() }
    return NoteListText(named, (if (named.isNotBlank()) aboutLine().ifBlank { text } else text).take(400))
}
