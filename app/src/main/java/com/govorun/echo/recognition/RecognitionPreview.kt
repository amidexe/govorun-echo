package com.govorun.echo.recognition

import com.govorun.echo.notes.NoteImport
import com.govorun.echo.notes.CutModel
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Current file recognition, for listening while it runs. Never written as saved text, subtitles or a backup layer. */
internal object RecognitionPreview {
    data class Snapshot(val noteId: String, val text: String, val phrases: List<NoteStore.Phrase>)
    val current = MutableStateFlow<Snapshot?>(null)

    fun clear(noteId: String) { current.update { if (it?.noteId == noteId) null else it } }

    /** A text callback may precede its times: keep showing the complete text until the matching times arrive. */
    fun forNote(note: NoteStore.Entry, preview: Snapshot?): NoteStore.Entry? {
        if (note.mode != NoteImport.MODE || note.appending) return null
        if (note.state != NoteStore.STATE_RECOGNIZING || preview?.noteId != note.id || preview.text.isBlank()) return null
        if (!CutModel.textFollowsWords(preview.text, preview.phrases.flatMap { p -> p.words.map { it.text } })) {
            return note.copy(text = preview.text, phrases = emptyList(), timed = false)
        }
        return note.copy(text = preview.text, phrases = preview.phrases)
    }
}
