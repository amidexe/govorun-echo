package com.govorun.echo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import com.product.ui.ProductTokens
import kotlinx.coroutines.flow.collectLatest

/**
 * The text being edited on the note's screen, so its undo, redo and «done» stand in the note's top bar while it is edited — up
 * where the keyboard never covers them and a long text need not be scrolled to its end. One editor is edited at a time.
 */
internal object TextEditing {
    var field by mutableStateOf<TextFieldState?>(null)
        private set
    private var save: (() -> Unit)? = null
    private var cancel: (() -> Unit)? = null

    fun attach(
        state: TextFieldState,
        onSave: (() -> Unit)?,
        onCancel: (() -> Unit)?,
    ) {
        field = state
        save = onSave
        cancel = onCancel
    }

    fun detach(state: TextFieldState) {
        if (field === state) {
            field = null
            save = null
            cancel = null
        }
    }

    fun save() = save
    fun cancel() = cancel
}

/** Undo, redo, and «done» (with «cancel» where an edit can be dropped) for the text being edited, in the top bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowScope.EditingActions(showCancel: Boolean = true, enabled: Boolean = true, preview: FairState? = null) {
    val field = TextEditing.field ?: return
    val focus = LocalFocusManager.current
    val size = Modifier.size(ProductTokens.Size.TopBarActionIcon)
    IconButton(onClick = { if (field.undoState.canUndo) field.undoState.undo() else preview?.undoPreview() }, enabled = enabled && (field.undoState.canUndo || preview?.canUndoPreview == true)) { Icon(PhosphorIcons.Regular.ArrowCounterClockwise, contentDescription = stringResource(R.string.text_undo), modifier = size) }
    IconButton(onClick = { if (field.undoState.canRedo) field.undoState.redo() else preview?.redoPreview() }, enabled = enabled && (field.undoState.canRedo || preview?.canRedoPreview == true)) { Icon(PhosphorIcons.Regular.ArrowClockwise, contentDescription = stringResource(R.string.text_redo), modifier = size) }
    TextEditing.cancel()?.takeIf { showCancel }?.let { cancel -> IconButton(onClick = cancel) { Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.fair_cancel), modifier = size) } }
    val save = TextEditing.save()
    IconButton(enabled = enabled, onClick = { save?.invoke() ?: focus.clearFocus() }) {
        Icon(PhosphorIcons.Regular.Check, contentDescription = stringResource(if (save == null) R.string.action_done else R.string.fair_save), tint = MaterialTheme.colorScheme.primary, modifier = size)
    }
}

/**
 * The one text editor of Echo, for the working text and a note of text only. Formatted working text hides its syntax
 * without changing the source held by the field, its drafts or undo history;
 * selecting, copying and pasting as anywhere on Android; its undo, redo and «done» live in the note's top bar ([EditingActions]).
 * With [onSave] it is an edit to keep or drop, shown in the bar for as long as it is open; without, a text saved as typed, in the
 * bar while the keyboard is up.
 */
@Composable
internal fun TextEditor(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    hint: String = "",
    autoFocus: Boolean = false,
    readOnly: Boolean = false,
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    formatted: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    val saveAction by rememberUpdatedState(onSave)
    val cancelAction by rememberUpdatedState(onCancel)
    if (onSave != null) {
        DisposableEffect(state) {
            TextEditing.attach(state, { saveAction?.invoke() }, onCancel?.let { { cancelAction?.invoke() } })
            onDispose { TextEditing.detach(state) }
        }
    } else {
        DisposableEffect(state) { onDispose { TextEditing.detach(state) } }
    }
    BasicTextField(
        state = state,
        inputTransformation = if (formatted) EditorMarkdown else null,
        outputTransformation = if (formatted) EditorMarkdown else null,
        readOnly = readOnly,
        modifier =
            modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                if (onSave == null) if (it.isFocused) TextEditing.attach(state, null, null) else TextEditing.detach(state)
            },
        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorator = { field ->
            if (state.text.isEmpty() && hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            field()
        },
    )
}

/** Puts [text] in to be edited, with no undo back past it. */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldState.load(text: String) {
    setTextAndPlaceCursorAtEnd(text)
    undoState.clearHistory()
}

/** The two choices of a new text note, shared in or just typed: let it go, or keep it. */
@Composable
internal fun TextDraftBar(
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    NoteActionBar {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.text_discard)) }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.text_save)) }
        }
    }
}

/** The text of a note without sound, saved as it is typed; a new note left empty goes without a trace when it is left. */
@Composable
internal fun TextNoteBody(
    note: NoteStore.Entry,
    actions: NotesActions,
) {
    val context = LocalContext.current
    val root = remember { NoteStore.root(context) }
    val state = remember(note.id) { TextFieldState().apply { load(note.text) } }
    val changed by rememberUpdatedState(actions.onChanged)
    LaunchedEffect(note.id) {
        snapshotFlow { state.text.toString() }.collectLatest { text: String ->
            kotlinx.coroutines.delay(TEXT_SAVE_AFTER_MS)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NoteText.setText(root, note.id, text) }
            changed()
        }
    }
    DisposableEffect(note.id) {
        onDispose {
            NoteText.setText(root, note.id, state.text.toString())
            if (!NoteText.dropIfEmpty(root, note.id)) NoteText.keep(root, note.id)
            changed()
        }
    }
    TextEditor(state, hint = stringResource(R.string.text_hint), autoFocus = note.text.isBlank())
}

private const val TEXT_SAVE_AFTER_MS = 600L
