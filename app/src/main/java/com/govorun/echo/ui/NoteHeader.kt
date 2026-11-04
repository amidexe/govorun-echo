package com.govorun.echo.ui

import com.govorun.echo.notes.aboutLine
import com.govorun.echo.notes.smartName
import com.govorun.echo.notes.typedTitle
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import kotlinx.coroutines.delay

/**
 * The top of a note's text card, as in Google Keep: the note's name, typed right where it is shown. The note's tags are at the end of
 * the text ([NoteTagLine]), quiet, so the top stays the name and what was said.
 *
 * The name is a line of the card that reads «Название» (or the name the smart functions gave) in a quiet colour until something is typed. A tap puts the cursor there;
 * «Готово», the Enter key or hiding the keyboard ends the edit, and what was typed is kept by itself, with no button to press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteHeader(
    note: NoteStore.Entry,
    onTitle: (String) -> Unit,
    /** Told when typing the name begins and ends, so the card does not scroll away from it under the keyboard. */
    onEditing: (Boolean) -> Unit = {},
    /** What the note is about, as its owner writes it under the name. */
    onAbout: (String) -> Unit = {},
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var field by remember(note.id) { mutableStateOf(TextFieldValue(note.typedTitle(), TextRange(note.typedTitle().length))) }
    var focused by remember(note.id) { mutableStateOf(false) }
    // Kept a moment after the last key, and at once when the edit ends or the screen goes; the same name is not written twice.
    val typed by rememberUpdatedState(field.text)
    val stored by rememberUpdatedState(note.typedTitle())
    val write by rememberUpdatedState(onTitle)
    val save = { if (NoteStore.cleanTitle(typed) != stored) write(typed) }
    LaunchedEffect(note.id, field.text) {
        delay(SAVE_AFTER_MS)
        save()
    }
    DisposableEffect(note.id) { onDispose { save() } }
    // The keyboard put away by the back gesture ends the edit too, so no cursor is left blinking in a name that is done.
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard && focused) focus.clearFocus() }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    // A name is one line: the Enter key ends it as «Готово» does, and a pasted line break becomes a space.
                    val enter = next.text.length == field.text.length + 1 && next.text.replace("\n", "") == field.text
                    if (enter) {
                        focus.clearFocus()
                    } else {
                        val text = next.text.replace('\n', ' ').take(NoteStore.MAX_TITLE)
                        field = next.copy(text = text, selection = TextRange(next.selection.start.coerceAtMost(text.length), next.selection.end.coerceAtMost(text.length)))
                    }
                },
                modifier =
                    Modifier.weight(1f).onFocusChanged {
                        if (focused != it.isFocused) onEditing(it.isFocused)
                        focused = it.isFocused
                        if (!it.isFocused) save()
                    },
                textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { input ->
                    Box {
                        // The smart functions' name waits there in the same quiet colour: typed over, the owner's own name replaces it.
                        if (field.text.isEmpty()) Text(note.smartName().ifBlank { stringResource(R.string.note_title_hint) }, style = MaterialTheme.typography.titleLarge, color = quiet.copy(alpha = 0.6f), maxLines = 2)
                        input()
                    }
                },
            )
            trailing()
        }
        AboutField(note, onAbout, onEditing)
    }
}

/**
 * «О чём» under the name, always in its place: a line or two in the owner's own words, to find one's way among the notes later.
 * Empty, it shows what a fair copy from Govorun suggested, in the quiet colour, or the hint; typed over, the owner's words win.
 */
@Composable
private fun AboutField(
    note: NoteStore.Entry,
    onAbout: (String) -> Unit,
    onEditing: (Boolean) -> Unit,
) {
    var text by remember(note.id) { mutableStateOf(note.about) }
    val write by rememberUpdatedState(onAbout)
    val save = { if (text.trim() != note.about) write(text) }
    LaunchedEffect(note.id, text) {
        delay(SAVE_AFTER_MS)
        save()
    }
    DisposableEffect(note.id) { onDispose { save() } }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(NoteStore.MAX_ABOUT) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused); if (!it.isFocused) save() },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = quiet),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { input ->
            Box {
                if (text.isEmpty()) Text(note.aboutLine().takeIf { it.isNotBlank() } ?: stringResource(R.string.note_about_hint), style = MaterialTheme.typography.bodyMedium, color = quiet.copy(alpha = 0.6f), maxLines = 3)
                input()
            }
        },
    )
}

private const val SAVE_AFTER_MS = 600L
