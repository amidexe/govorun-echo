package com.govorun.echo.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.recognition.RecognitionClient
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wires note actions to root-owned state and existing operation hosts. */
internal fun MainActivity.createNotesActions(
    ui: NotesUiState,
    navigation: AppNavigation,
    scope: CoroutineScope,
    client: RecognitionClient,
    playing: () -> NotePlayer.State,
    onGetGovorun: () -> Unit,
    snackbar: SnackbarHostState,
    deleteBatch: DeleteBatch,
    trashCount: Int,
    tags: TagBook,
    microphone: Boolean,
    requestPermissions: () -> Unit,
    onAddFiles: () -> Unit,
    reload: () -> Unit,
    onHelp: () -> Unit,
): NotesActions =
    with(ui) {
        NotesActions(
            onCardClick = { id ->
                if (selected.isNotEmpty() || selectMode) {
                    selected = if (id in selected) selected - id else selected + id
                } else {
                    com.govorun.echo.notes.NoteSeen.set(
                        NoteStore.root(this@createNotesActions),
                        id,
                        false,
                    )
                    reload()
                    navigation.open(Screen.Note, id, query)
                }
            },
            onSetSeen = { ids, unseen ->
                markSelectedSeen(
                    this@createNotesActions,
                    scope,
                    ids,
                    unseen,
                    {
                        reload()
                        Unit
                    },
                ) { left ->
                    selected = (selected - ids.toSet()) + left
                    selectMode = selected.isNotEmpty()
                }
            },
            onCardLongClick = { id ->
                selected = selected + id
                selectMode = true
            },
            onSetFavorites = { ids, on ->
                markSelectedFavorites(this@createNotesActions, scope, ids, on, { reload() }) { left ->
                    selected = (selected - ids.toSet()) + left
                    selectMode = selected.isNotEmpty()
                }
            },
            onNewText = {
                val id = NoteText.create(NoteStore.root(this@createNotesActions))
                changes++
                navigation.open(Screen.Note, id)
            },
            onRecord = {
                player.stop()
                // A new recording made from inside a note is shown in the list, where its card
                // is.
                if (navigation.current.screen == Screen.Note) navigation.showNotes()
                if (microphone) {
                    startForegroundService(
                        RecordingService.startIntent(
                            this@createNotesActions,
                            RecordingService.MODE_LONG,
                            tag = filter.tag,
                        )
                    )
                } else {
                    requestPermissions()
                }
            },
            onStop = {
                RecordingBus.stopping.value = true
                startService(RecordingService.stopIntent(this@createNotesActions))
            },
            onPauseRecording = { on ->
                startService(RecordingService.pauseIntent(this@createNotesActions, on))
            },
            onSettings = { navigation.open(Screen.Settings) },
            onTrash = { navigation.open(Screen.Trash) },
            trashCount = trashCount,
            onHelp = { onHelp() },
            onOpenFilters = { filtersOpen = true },
            onFilter = { filter = it },
            onTab = {
                navigation.showNotes()
                tab = it
                filter = NotesFilter()
                query = null
            },
            onToggleSearch = {
                searchFocusRequested = query == null
                query = if (searchFocusRequested) "" else null
            },
            takeSearchFocusRequest = {
                searchFocusRequested.also { searchFocusRequested = false }
            },
            onQueryChange = { query = it },
            onClearSelection = {
                selected = emptySet()
                selectMode = false
            },
            onCopy = { ids ->
                copyText(
                    joinedText(
                        notes.chronologicalOf(ids),
                        AppSettings.textOptions(this@createNotesActions),
                    )
                )
            },
            onShare = { ids ->
                shareText(
                    joinedText(
                        notes.chronologicalOf(ids),
                        AppSettings.textOptions(this@createNotesActions),
                    )
                )
            },
            onSplit = { id ->
                val source = navigation.current
                splitNote(id, { playing().noteId }, player, scope) {
                    scope.launch {
                        navigation.back(source)
                        reload()
                    }
                }
            },
            onMerge = { ids ->
                mergeNotes(ids, scope) {
                    selected = emptySet()
                    selectMode = false
                    reload()
                }
            },
            onDelete = onDelete@{ ids ->
                    if (notes.any { it.id in ids && it.archived } && !archiveDeleteConfirmed) {
                        // The archive is meant to keep things for good: ask before deleting
                        // from it.
                        confirmDialog(
                            R.string.dialog_delete_archive_title,
                            R.string.dialog_delete_archive_text,
                            R.string.action_delete,
                        ) {
                            archiveDeleteConfirmed = true
                            notesActionsRef?.onDelete?.invoke(ids)
                            archiveDeleteConfirmed = false
                        }
                        return@onDelete
                    }
                    deleteNotes(
                        notes,
                        ids,
                        playing().noteId,
                        player,
                        scope,
                        deleteBatch,
                        snackbar,
                        onChanged = {
                            selected = emptySet()
                            selectMode = false
                            reload()
                        },
                        onRefresh = { reload() },
                    )
                },
            onPlay = { id ->
                notes
                    .firstOrNull { it.id == id && it.state != NoteStore.STATE_RECORDING }
                    ?.let {
                        player.toggle(id, NoteStore.noteDir(this@createNotesActions, id), it.audio)
                    }
            },
            onSeek = { player.seekTo(it) },
            onPlayFrom = { id, ms ->
                notes
                    .firstOrNull { it.id == id && it.state != NoteStore.STATE_RECORDING }
                    ?.let {
                        player.playFrom(
                            id,
                            NoteStore.noteDir(this@createNotesActions, id),
                            it.audio,
                            ms,
                        )
                    }
            },
            onAppend = { id ->
                player.stop()
                if (microphone) {
                    scope.launch {
                        appendRecording(id, { notes }, snackbar) {
                            notesActionsRef?.onAuthorizeGovorun?.invoke()
                        }
                    }
                } else {
                    requestPermissions()
                }
            },
            onDeleteAudio = { id ->
                confirmDialog(
                    R.string.dialog_delete_audio_title,
                    R.string.dialog_delete_audio_text,
                    R.string.action_delete_audio,
                ) {
                    if (playing().noteId == id) player.stop()
                    NoteStore.deleteAudio(NoteStore.noteDir(this@createNotesActions, id))
                    reload()
                }
            },
            onExport = { ids ->
                // No selection in the archive means "the whole archive".
                val targets =
                    if (ids.isEmpty()) notes.filter { it.archived }.sortedBy { it.startedAtMs }
                    else notes.chronologicalOf(ids)
                exporter.exportNotes(targets)
            },
            onArchive = { ids, archive ->
                val root = NoteStore.root(this@createNotesActions)
                NoteStore.setArchived(root, ids, archive)
                selected = emptySet()
                selectMode = false
                reload()
                if (archive) ActionSounds.play(this@createNotesActions, ActionSounds.ARCHIVE)
                scope.launch {
                    announceArchive(snackbar, ids, archive) {
                        NoteStore.setArchived(root, ids, !archive).also { reload() }
                    }
                }
            },
            onRetry = { id ->
                startForegroundService(
                        RecordingService.transcribeIntent(this@createNotesActions, id)
                    )
                    .also {
                        scope.launch {
                            delay(800)
                            changes++
                        }
                    }
            },
            onAddFiles = onAddFiles,
            onPostpone = { id ->
                startService(RecordingService.postponeIntent(this@createNotesActions, id)).also {
                    scope.launch {
                        delay(800)
                        changes++
                    }
                }
            },
            onSetTag = { ids, tag, add ->
                TagHost.set(this@createNotesActions, ids, tag, add).also { reload() }
            },
            onSetTitle = { id, title ->
                NoteStore.setTitle(NoteStore.root(this@createNotesActions), id, title).also {
                    reload()
                }
            },
            onTagFilter = { filter = filter.withoutTags().copy(tag = it) },
            allTags = tags.all,
            madeTags = tags.made,
            tagStyles = tags.styles,
            pinnedTags = tags.pinned,
            tagUse = tags.use,
            onTagOrder = {
                TagHost.reorder(this@createNotesActions, it, tags.pinned).also { changes++ }
            },
            onTagStyle = { tag, style ->
                TagHost.recolour(this@createNotesActions, tag, style.color).also { changes++ }
            },
            onNewTag = { name, color ->
                TagHost.create(this@createNotesActions, name, color).also { changes++ }
            },
            onDeleteTag = { tag ->
                TagHost.delete(this@createNotesActions, notes, tag).also { reload() }
            },
            onPinTag = { tag, on ->
                TagHost.pin(this@createNotesActions, tag, on).also { changes++ }
            },
            onRenameTag = { from, to, short ->
                TagHost.rename(this@createNotesActions, notes, from, to, short).also {
                    if (filter.tag == from) filter = filter.copy(tag = to)
                    reload()
                }
            },
            onChanged = { reload() },
            onRequestMicrophone = {
                requestPermissions()
            },
            onAuthorizeGovorun = {
                scope.launch {
                    client.authorizationRequest()?.let { client.launchAuthorization(it) }
                    changes++
                }
            },
            onGetGovorun = onGetGovorun,
            onPlayPieces = { id, spans, start, total ->
                notes
                    .firstOrNull { it.id == id }
                    ?.let {
                        player.playPieces(
                            id,
                            NoteStore.noteDir(this@createNotesActions, id),
                            it.audio,
                            spans,
                            start,
                            total,
                        )
                    }
            },
            edits = fragments.bind(scope, snackbar) { reload() },
        )
    }
