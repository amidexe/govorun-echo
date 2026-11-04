package com.govorun.echo.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteTopBar(
    note: NoteStore.Entry,
    fairState: FairState,
    itogiState: ItogiState,
    current: FragmentDraft?,
    shownFind: FindInNote?,
    canEdit: Boolean,
    busy: Boolean,
    actions: NotesActions,
    goBack: () -> Unit,
    onFragmentBack: () -> Unit,
    stepFind: (Int) -> Unit,
    onFindClose: () -> Unit,
    onFragmentUndo: () -> Unit,
    onFragmentSave: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    TopAppBar(
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        navigationIcon = {
            if (current != null) {
                IconButton(onClick = onFragmentBack) {
                    Icon(
                        PhosphorIcons.Regular.X,
                        contentDescription = stringResource(R.string.fragment_exit),
                        modifier = Modifier.size(ProductTokens.Size.TopBarNavigationIcon),
                    )
                }
            } else {
                IconButton(onClick = goBack) {
                    Icon(
                        PhosphorIcons.Regular.ArrowLeft,
                        contentDescription = stringResource(R.string.action_back),
                        modifier = Modifier.size(ProductTokens.Size.TopBarNavigationIcon),
                    )
                }
            }
        },
        title = {
            when {
                fairState.editing -> Text(stringResource(R.string.text_editing))
                current != null -> Text(stringResource(R.string.fragment_title))
                shownFind != null ->
                    Text(
                        stringResource(
                            R.string.find_in_note,
                            shownFind.query.trim(),
                            shownFind.current + 1,
                            shownFind.size,
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                else ->
                    Text(
                        "${dayLabel(context, dayOf(note.startedAtMs))}, ${formatTime(note.startedAtMs)}",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
            }
        },
        actions = {
            if (TextEditing.field != null) {
                FairEditorAction(note, fairState)
                EditingActions(
                    showCancel = false,
                    enabled = !fairState.busy,
                    preview = fairState,
                )
            } else if (current == null && shownFind != null) {
                IconButton(onClick = { stepFind(-1) }) {
                    Icon(
                        PhosphorIcons.Regular.CaretUp,
                        contentDescription = stringResource(R.string.find_previous),
                        modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                    )
                }
                IconButton(onClick = { stepFind(1) }) {
                    Icon(
                        PhosphorIcons.Regular.CaretDown,
                        contentDescription = stringResource(R.string.find_next),
                        modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                    )
                }
                IconButton(onClick = onFindClose) {
                    Icon(
                        PhosphorIcons.Regular.X,
                        contentDescription = stringResource(R.string.find_close),
                        modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                    )
                }
            } else if (current != null) {
                if (canEdit) {
                    IconButton(
                        onClick = onFragmentUndo,
                        enabled = current.changed && !busy,
                    ) {
                        Icon(
                            PhosphorIcons.Regular.ArrowUUpLeft,
                            contentDescription = stringResource(R.string.fragment_undo),
                            modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = onFragmentSave,
                        enabled = current.changed && !busy,
                    ) {
                        Text(stringResource(R.string.fragment_save))
                    }
                }
            } else {
                if ((displayedNoteMarkdown(note, fairState, itogiState) ?: note.text).isNotBlank()) {
                    IconButton(
                        onClick = {
                            displayedNoteMarkdown(note, fairState, itogiState)?.let {
                                (context as? android.app.Activity)?.shareText(it)
                            } ?: actions.onShare(listOf(note.id))
                        }
                    ) {
                        Icon(
                            PhosphorIcons.Regular.ShareNetwork,
                            contentDescription = stringResource(R.string.action_share),
                            modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                        )
                    }
                }
                IconButton(onClick = onMenu) {
                    Icon(
                        PhosphorIcons.Regular.DotsThree,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon),
                    )
                }
            }
        },
    )
}
