package com.govorun.echo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import com.govorun.echo.playback.NotePlayer
import com.product.ui.ProductTokens

/**
 * One note on its own screen, built from the same cards as the list: a player card with a waveform
 * (speech and silence are visible) and a text card that scrolls and, while the note plays, follows
 * the spoken word like subtitles.
 *
 * «Фрагмент» (from the menu) edits the note the way Android's own editors do: a cross on the left
 * leaves without saving (asking first when something changed), «Сохранить» on the right keeps the
 * result, and «Отменить шаг» takes the last step back. Until «Сохранить» everything is a draft: the
 * scale, the text and ▶ show the note as it will be, the files stay as they are.
 */
@Composable
internal fun NoteScreen(
    note: NoteStore.Entry,
    player: NotePlayer.State?,
    recording: Boolean,
    actions: NotesActions,
    snackbar: androidx.compose.material3.SnackbarHostState,
    /** The search the note was opened from: its words are lit in the text, one after another. */
    query: String? = null,
    onBack: () -> Unit,
) {
    // The list holds no word times: they are read for the open note, and its text waits for them
    // instead of changing its look.
    val full = rememberWords(note)
    NoteScreenContent(
        full ?: note,
        full != null,
        player,
        recording,
        actions,
        snackbar,
        query,
        onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteScreenContent(
    note: NoteStore.Entry,
    wordsRead: Boolean,
    player: NotePlayer.State?,
    recording: Boolean,
    actions: NotesActions,
    snackbar: androidx.compose.material3.SnackbarHostState,
    query: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val smartChoice by com.govorun.echo.smart.Smart.optedIn.collectAsState()
    val fairState = rememberFairState(note.id)
    val itogiState = rememberItogiState(note.id)
    val goBack = {
        if (fairState.editing) {
            if (fairState.changed || fairState.busy) fairState.leaving = true
            else fairState.cancel(context, note)
        } else onBack()
    }
    BackHandler(enabled = fairState.editing) { goBack() }
    // The note opened: Govorun's words for what it keeps of it, and what it has answered meanwhile
    // is collected.
    if (com.govorun.echo.smart.Smart.available) {
        val appContext = LocalContext.current.applicationContext
        androidx.compose.runtime.LaunchedEffect(note.id, smartChoice) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.govorun.echo.smart.FairQueue.sync(appContext)
            }
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    // «Фрагмент»: the draft (null outside the mode), the piece chosen in it, and whether the piece
    // was chosen in the text.
    var draft by remember(note.id) { mutableStateOf<FragmentDraft?>(null) }
    var pick by remember(note.id) { mutableStateOf(FragmentSelection(0L, 0L)) }
    var pickedHere by remember(note.id) { mutableStateOf(false) }
    var busy by remember(note.id) { mutableStateOf(false) }
    var leaving by remember(note.id) { mutableStateOf(false) }
    // The piece each step cut or kept, so «Отменить шаг» shows it again where it came back.
    var stepPicks by remember(note.id) { mutableStateOf(emptyList<FragmentSelection>()) }
    // A finished note with sound can give a piece; cutting also needs its text to be its timed
    // words (so the text can follow the
    // sound), and a joined note keeps the passport of its parts, which a cut would break.
    val canFragment =
        note.state == NoteStore.STATE_DONE &&
            note.audio.isNotEmpty() &&
            !note.archiving &&
            !note.appending
    val noteEdges =
        remember(note.phrases, note.audio) {
            FragmentEdges(note.phrases, note.audio.sumOf { it.ms })
        }
    val canEdit =
        remember(note.text, note.phrases, note.parts, canFragment) {
            canFragment &&
                note.parts.isEmpty() &&
                (note.text.isBlank() ||
                    com.govorun.echo.notes.CutModel.textFollowsWords(
                        note.text,
                        noteEdges.words.map { it.text },
                    ))
        }
    // New sound under the draft (saved, continued): the mode ends.
    LaunchedEffect(note.audio) { draft = null }
    val notePlayer = remember { NotePlayer.get(context) }
    val playingPieces = player?.playing == true && player.rangeEndMs != null
    val choose = { piece: FragmentSelection, inText: Boolean ->
        if (playingPieces) notePlayer.pause()
        pick = piece
        pickedHere = inText
    }
    val enter = { piece: FragmentSelection ->
        if (player?.playing == true) notePlayer.pause()
        draft = FragmentDraft.of(note)
        stepPicks = emptyList()
        choose(piece, false)
    }
    val leave = {
        if (playingPieces) notePlayer.stop()
        draft = null
        leaving = false
    }
    val current = draft
    BackHandler(enabled = current != null) {
        if (current?.changed == true) leaving = true else leave()
    }
    val draftText = remember(current) { current?.let { DraftText.of(it.phrases) } }
    val draftEdges = remember(current) { current?.let { FragmentEdges(it.phrases, it.totalMs) } }
    var peaks by remember(note.id) { mutableStateOf<ByteArray?>(null) }
    if (current != null)
        LaunchedEffect(note.id, note.audio) {
            peaks =
                com.govorun.echo.notes.AudioPeaks.load(
                    NoteStore.noteDir(context, note.id),
                    note.audio,
                )
        }
    val draftBars = remember(current, peaks) { current?.bars(peaks) }
    // Opened from a search: its words are lit and the top bar steps through them, the player
    // following, until it is closed.
    val find = rememberFindInNote(note, wordsRead, query, player?.positionMs)
    var finding by remember(find) { mutableStateOf(find != null) }
    val shownFind = find?.takeIf { finding && current == null }
    val stepFind = { by: Int ->
        if (shownFind != null) {
            shownFind.move(by)
            val at = shownFind.finds[shownFind.current]
            if (note.phrases.isNotEmpty() && note.audio.isNotEmpty())
                notePlayer.placeAt(note.id, NoteStore.noteDir(context, note.id), note.audio, at.ms)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NoteTopBar(
                note,
                fairState,
                itogiState,
                current,
                shownFind,
                canEdit,
                busy,
                actions,
                goBack = goBack,
                onFragmentBack = { if (current?.changed == true) leaving = true else leave() },
                stepFind = stepFind,
                onFindClose = { finding = false },
                onFragmentUndo = {
                    if (current != null) {
                        draft = current.undo()
                        choose(stepPicks.lastOrNull() ?: FragmentSelection(0L, 0L), false)
                        stepPicks = stepPicks.dropLast(1)
                    }
                },
                onFragmentSave = {
                    if (current != null) {
                        busy = true
                        actions.edits.save(note.id, current.kept) { saved ->
                            busy = false
                            if (saved) leave()
                        }
                    }
                },
                onMenu = { menuOpen = true },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Inside a finished note the microphone continues that note; elsewhere it starts a new
            // one.
            // The big button continues this note, also in the second after a stop while its text is
            // being finished.
            // A note left with its text only (its sound deleted) is not continued: the button
            // starts a new note.
            val appendable =
                note.state != NoteStore.STATE_RECORDING &&
                    (note.audio.isNotEmpty() || note.state != NoteStore.STATE_DONE)
            // The player panel stays put from recording to done: while the sound is still being
            // prepared it is the same panel, just not playable yet.
            val showsPlayer =
                note.audio.isNotEmpty() ||
                    note.state == NoteStore.STATE_RECORDING ||
                    note.state == NoteStore.STATE_RECOGNIZING
            Column(Modifier.fillMaxSize()) {
                // A text editing session owns the screen; the source player belongs to the
                // read-only transcript.
                if (
                    showsPlayer &&
                        !fairState.editing && !fairState.shown &&
                        (TextEditing.field == null || player?.playing == true)
                ) {
                    // Play on the left, record on the right: the one panel for listening and for
                    // continuing the note.
                    androidx.compose.material3.Surface(
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).fillMaxWidth(),
                        shape =
                            androidx.compose.foundation.shape.RoundedCornerShape(
                                ProductTokens.Radius.Card
                            ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (current != null && draftEdges != null) {
                            FragmentPanel(
                                draftBars,
                                current.totalMs,
                                pick,
                                playing = playingPieces,
                                positionMs = player?.positionMs,
                                onPick = { choose(it, false) },
                                onPlay = {
                                    if (playingPieces) {
                                        notePlayer.pause()
                                    } else {
                                        // The piece, or from the cursor to the end: so a join left
                                        // by a cut can be heard.
                                        val to =
                                            if (pick.lengthMs > 0) pick.endMs else current.totalMs
                                        actions.onPlayPieces(
                                            note.id,
                                            current.source(pick.startMs, to).map {
                                                it.fromMs.toLong() to it.toMs.toLong()
                                            },
                                            pick.startMs,
                                            current.totalMs,
                                        )
                                    }
                                },
                                modifier =
                                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                            )
                        } else {
                            InlinePlayer(
                                note,
                                player ?: NotePlayer.State(),
                                actions,
                                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                standalone = true,
                                // «Дописать запись» belongs to the sound: on the «Текст» tab, which
                                // is only text, it is not offered.
                                onRecord =
                                    if (
                                        (fairState.shown || itogiState.shown) &&
                                            note.state == NoteStore.STATE_DONE
                                    )
                                        null
                                    else if (appendable) ({ actions.onAppend(note.id) })
                                    else actions.onRecord,
                            )
                        }
                    }
                }
                if (current != null && draftText != null && draftEdges != null) {
                    FragmentTextCard(
                        draftText,
                        draftEdges,
                        pick,
                        pickedHere,
                        onPick = { choose(it, true) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val itogiTab = itogiState.tab(context, note)
                    val tabs:
                        (@Composable
                        (
                            @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
                        ) -> Unit)? =
                        if (fairState.tabs(note) || itogiTab)
                            ({ tools ->
                                FairTabs(
                                    fairState,
                                    typed = note.mode == NoteText.MODE,
                                    hasText = fairState.of(note) != null,
                                    itogi = itogiState.takeIf { itogiTab },
                                    trailing = {
                                        tools()
                                        if (!itogiState.shown) NoteEditTool(note, fairState)
                                    },
                                )
                            })
                        else null
                    if (itogiState.shown && itogiTab)
                        ItogiCard(
                            note,
                            itogiState,
                            actions.onChanged,
                            Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            tabs,
                        )
                    else if (
                        fairState.shown ||
                            (note.mode == NoteText.MODE &&
                                !remember(note.id) {
                                    NoteText.isDraft(NoteStore.root(context), note.id)
                                })
                    )
                        FairCard(
                            note,
                            fairState,
                            actions,
                            Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            tabs,
                        )
                    else
                        NoteTextCard(
                            note,
                            player,
                            actions,
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            onTags = { tagging = true },
                            wordsRead = wordsRead,
                            find = shownFind,
                            fairState = fairState,
                            tabs = tabs,
                        )
                }
            }
            // A note of text only records nothing: its own new note asks to be kept or let go
            // instead of a «+» that would record.
            val textOnly = note.audio.isEmpty() && note.state == NoteStore.STATE_DONE
            if (
                textOnly && remember(note.id) { NoteText.isDraft(NoteStore.root(context), note.id) }
            )
                TextDraftBar(
                    Modifier.align(Alignment.BottomCenter),
                    onDiscard = {
                        NoteText.discard(NoteStore.root(context), note.id)
                        actions.onChanged()
                        onBack()
                    },
                ) {
                    NoteText.keep(NoteStore.root(context), note.id)
                    onBack()
                }
            if (!showsPlayer && !textOnly)
                RecordButton(
                    recording,
                    actions,
                    Modifier.align(Alignment.BottomEnd),
                    onRecord =
                        if (appendable) ({ actions.onAppend(note.id) }) else actions.onRecord,
                    recordLabel =
                        if (appendable) R.string.action_append else R.string.action_new_recording,
                )
            if (current != null) {
                val whole = pick.startMs <= 0L && pick.endMs >= current.totalMs
                val spans = { current.source(pick.startMs, pick.endMs) }
                FragmentActions(
                    canEdit = canEdit,
                    hasPiece = pick.lengthMs > 0,
                    wholeNote = whole,
                    busy = busy,
                    onSeparate = {
                        busy = true
                        actions.edits.separate(note.id, spans()) { busy = false }
                    },
                    onCut = {
                        val next = current.cut(pick.startMs, pick.endMs)
                        if (next !== current) {
                            stepPicks = stepPicks + pick
                            draft = next
                            choose(FragmentSelection(pick.startMs, pick.startMs), false)
                        }
                    },
                    onKeep = {
                        val next = current.keepOnly(pick.startMs, pick.endMs)
                        if (next !== current) {
                            stepPicks = stepPicks + pick
                            draft = next
                            choose(FragmentSelection(0L, 0L), false)
                        }
                    },
                    onShare = {
                        busy = true
                        actions.edits.share(note.id, spans()) { busy = false }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            androidx.compose.material3.SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (current != null) 88.dp else 16.dp),
            ) { data ->
                androidx.compose.material3.Snackbar(
                    data,
                    shape = RoundedCornerShape(ProductTokens.Radius.Card),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (leaving) {
        // Leaving with changes not saved: the one question Android editors ask.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.fragment_discard_title)) },
            text = { Text(stringResource(R.string.fragment_discard_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = leave) {
                    Text(stringResource(R.string.fragment_discard))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { leaving = false }) {
                    Text(stringResource(R.string.fragment_keep_editing))
                }
            },
        )
    }
    if (menuOpen)
        NoteScreenMenu(
            note,
            actions,
            fairState,
            itogiState,
            onTags = { tagging = true },
            onFragment =
                if (canFragment) ({ enter(noteEdges.initial(player?.positionMs)) }) else null,
            onDismiss = { menuOpen = false },
        )
    if (fairState.choosing) FairStyleSheet(note, fairState, actions)
    FairEditGuard(note, fairState, itogiState, actions.onChanged)
    if (tagging)
        NoteTagsSheet(
            listOf(note),
            actions,
            { tag, add -> actions.onSetTag(listOf(note.id), tag, add) },
        ) {
            tagging = false
        }
}
