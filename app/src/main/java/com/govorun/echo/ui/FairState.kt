package com.govorun.echo.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.govorun.echo.notes.NoteEditDraft
import com.govorun.echo.notes.NoteFair
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import com.govorun.echo.smart.FairQueue
import com.govorun.echo.smart.FairRuns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A saved working text and a distinct editor preview; only Save replaces the working text. */
internal class FairState(
    private val scope: CoroutineScope,
    shownState: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false),
) {
    var shown by shownState
    var making by mutableStateOf(false)
    var awaiting by mutableStateOf(false)
    val busy: Boolean get() = making || awaiting
    var run by mutableStateOf<FairRuns.Run?>(null)
    var failed by mutableStateOf<String?>(null)
    var made by mutableStateOf<NoteFair?>(null)
    fun of(note: NoteStore.Entry): NoteFair? = made ?: if (note.mode == NoteText.MODE) NoteFair("", "", note.text, byHand = true) else note.fair
    fun tabs(note: NoteStore.Entry): Boolean = of(note) != null || making || failed != null || note.state == NoteStore.STATE_DONE
    var choosing by mutableStateOf(false)
    var editing by mutableStateOf(false)
    private var readingBeforeEdit = false
    val draft = androidx.compose.foundation.text.input.TextFieldState()
    private var editingSource = ""
    private var editingText = ""
    val changed: Boolean get() = draft.text.toString().trim() != editingText.trim()
    var leaving by mutableStateOf(false)
    var saveFailed by mutableStateOf(false)
    private var beforeReplace by mutableStateOf<String?>(null)
    private var afterReplace by mutableStateOf<String?>(null)
    private var replacedUndone by mutableStateOf(false)
    val canUndoPreview: Boolean get() = !replacedUndone && beforeReplace != null && draft.text.toString() == afterReplace
    val canRedoPreview: Boolean get() = replacedUndone && draft.text.toString() == beforeReplace
    fun undoPreview() { if (canUndoPreview) { draft.load(beforeReplace!!); replacedUndone = true } }
    fun redoPreview() { if (canRedoPreview) { draft.load(afterReplace!!); replacedUndone = false } }
    private fun replace(text: String) {
        beforeReplace = draft.text.toString()
        afterReplace = text
        replacedUndone = false
        draft.load(text)
    }

    fun persist(context: Context, note: NoteStore.Entry) {
        if (!editing || busy) return
        runCatching {
            if (changed) NoteEditDraft.manual(NoteStore.root(context), note.id, editingText, editingSource, draft.text.toString())
            else { NoteEditDraft.discard(NoteStore.root(context), note.id); null }
        }.onSuccess { appliedPreview = it?.token }.onFailure { saveFailed = true }
    }

    fun save(context: Context, note: NoteStore.Entry, onChanged: () -> Unit, keepEditing: Boolean = false): Boolean {
        if (!changed) { if (!keepEditing) cancel(context, note); return true }
        val fair = of(note) ?: NoteFair("", "", editingSource, byHand = true)
        val kept = fair.copy(text = draft.text.toString().trim(), byHand = true, madeAtMs = System.currentTimeMillis())
        val saved = runCatching { NoteFair.write(NoteStore.root(context), note.id, editingSource, kept) }.getOrDefault(false)
        if (!saved) { saveFailed = true; return false }
        made = if (note.mode == NoteText.MODE) kept else NoteFair.read(NoteStore.root(context), note.id, note.text)
        discardPreview(context, note.id)
        shown = note.mode != NoteText.MODE && made != null
        editing = keepEditing
        leaving = false
        editingText = kept.text
        if (note.mode == NoteText.MODE) editingSource = kept.text
        saveFailed = false
        com.govorun.echo.backup.BackupFolder.syncAsync(context)
        onChanged()
        return true
    }

    fun restore(context: Context, note: NoteStore.Entry, onChanged: () -> Unit) {
        NoteFair.swapBefore(NoteStore.root(context), note.id, note.text)?.let { made = it; onChanged() }
    }

    private fun discardPreview(context: Context, id: String) {
        NoteEditDraft.discard(NoteStore.root(context), id)
        FairQueue.discardDraft(context, id)
        run = null
        making = false
        awaiting = false
        failed = null
    }

    fun resetOriginal(context: Context, note: NoteStore.Entry) {
        if (note.mode == NoteText.MODE) return
        discardPreview(context, note.id)
        replace(note.text)
        saveFailed = false
    }

    fun cancel(context: Context, note: NoteStore.Entry) {
        discardPreview(context, note.id)
        editing = false
        saveFailed = false
        leaving = false
        made = if (note.mode == NoteText.MODE) null else NoteFair.read(NoteStore.root(context), note.id, note.text)
        shown = readingBeforeEdit && note.mode != NoteText.MODE && of(note) != null
    }

    private var appliedPreview: String? = null
    fun refreshPreview(context: Context, note: NoteStore.Entry) {
        awaiting = FairQueue.entry(context, note.id)?.let { it.draftId != null && !it.failed } == true
        if (!editing) return
        val held = NoteEditDraft.resumed(NoteStore.root(context), note.id, editingText, editingSource) ?: return
        if (held.ready && appliedPreview != held.token) {
            replace(held.text)
            appliedPreview = held.token
            awaiting = false
        }
    }

    fun edit(note: NoteStore.Entry, context: Context? = null) {
        if (!editing) {
            readingBeforeEdit = shown
            editingText = of(note)?.text ?: note.text.trim()
            editingSource = note.text
            val recovered = context?.let { NoteEditDraft.resumed(NoteStore.root(it), note.id, editingText, editingSource) }
            draft.load(recovered?.text ?: editingText)
            appliedPreview = recovered?.takeIf { it.ready }?.token
            awaiting = context?.let { FairQueue.entry(it, note.id) }?.let { it.draftId != null && !it.failed } == true
            beforeReplace = null
            afterReplace = null
            saveFailed = false
            editing = true
        }
        shown = true
    }

    fun improveEditor(context: Context, note: NoteStore.Entry, onChanged: () -> Unit, style: String?, fromOriginal: Boolean) {
        val source = com.govorun.echo.smart.SmartFair.editorSource(note.mode, draft.text.toString(), fromOriginal)
        improve(context, note, onChanged, style, source)
    }

    fun improve(context: Context, note: NoteStore.Entry, onChanged: () -> Unit, style: String? = com.govorun.echo.AppSettings.fairStyleToRun(context), source: String? = null) {
        if (!editing) edit(note, context)
        val input = source ?: note.text
        if (input.isBlank()) { failed = com.govorun.echo.smart.GovorunSmart.TOO_SHORT; return }
        val held = runCatching { NoteEditDraft.begin(NoteStore.root(context), note.id, editingText, editingSource, draft.text.toString(), input) }.getOrNull()
        if (held == null) { saveFailed = true; return }
        awaiting = true
        if (!FairQueue.add(context, note.id, style, draftId = held.token)) {
            awaiting = false
            failed = if (com.govorun.echo.smart.Smart.enabled()) "queue_storage" else com.govorun.echo.smart.Smart.disabledReason()
            return
        }
        follow(FairRuns.start(context, note, style, onChanged, input, draftId = held.token), typed = note.mode == NoteText.MODE)
    }

    /** Completed previews update the live field; the last saved baseline stays unchanged until Save. */
    fun follow(run: FairRuns.Run, typed: Boolean = false) {
        this.run = run
        making = true
        failed = null
        scope.launch {
            val outcome = run.outcome.await()
            if (this@FairState.run !== run) return@launch
            making = false
            if (outcome.fair != null) {
                if (run.draftId == null) made = outcome.fair
                if (editing && appliedPreview != run.draftId) replace(outcome.fair.text)
                appliedPreview = run.draftId
                awaiting = false
            } else failed = outcome.reason.takeUnless { it in FairQueue.KEPT || it == FairQueue.GONE }
        }
    }
}
