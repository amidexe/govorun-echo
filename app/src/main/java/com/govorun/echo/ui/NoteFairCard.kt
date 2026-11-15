package com.govorun.echo.ui

import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.PencilSimple
import android.content.Context
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Sparkle
import com.govorun.echo.R
import com.govorun.echo.notes.NoteFair
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.smart.FairRuns
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import com.govorun.echo.smart.SmartFair
import com.product.ui.ProductTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import com.product.ui.ProductModalSheet


/**
 * The bar of a run: it fills by the time Govorun is expected to take and slows down past it, never quite full, so a slow
 * provider shows as slow, not as stuck. The run goes on with the screen closed; the bar picks it up again when it opens.
 */
@Composable
private fun FairProgress(run: FairRuns.Run?) {
    if (run == null) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val share by androidx.compose.runtime.produceState(0f, run) {
        while (true) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - run.startedAtMs
            value = (1 - kotlin.math.exp(-elapsed.toDouble() / run.expectedMs)).toFloat()
            kotlinx.coroutines.delay(200)
        }
    }
    androidx.compose.material3.LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
}

/** The fair-copy state of the note [noteId] on its screen; a run still going from before shows again as going. */
@Composable
internal fun rememberFairState(noteId: String): FairState {
    val scope = rememberCoroutineScope()
    val shown = rememberSaveable(noteId) { mutableStateOf(false) }
    return remember(noteId) { FairState(scope, shown).also { state -> FairRuns.running(noteId)?.let { state.follow(it) } } }
}

/** Whether «Улучшить через Говорун» is offered for [note]: a build that talks to Govorun and a note Govorun would improve. */
internal fun offersFair(note: NoteStore.Entry): Boolean = Smart.chosen() && SmartFair.fits(note)

/**
 * Source, saved working text (when present), and the optional summary share one reading card.
 */
@Composable
internal fun FairTabs(
    state: FairState,
    typed: Boolean = false,
    hasText: Boolean = false,
    /** The «Итоги» tab, when the note has итоги or they are being made. */
    itogi: ItogiState? = null,
    /** What the open tab can do, as icons at the end of the same row. */
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val itogiShown = itogi?.shown == true
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FairTab(stringResource(if (typed) R.string.fair_tab_fair else R.string.fair_tab_transcript), selected = (typed || !state.shown) && !itogiShown, icon = null) {
                state.shown = false
                itogi?.shown = false
            }
            if (!typed && hasText) {
                FairTab(stringResource(R.string.fair_tab_fair), selected = state.shown && !itogiShown, icon = null) {
                    state.shown = true
                    itogi?.shown = false
                }
            }
            if (itogi != null) {
                FairTab(stringResource(R.string.itogi_title), selected = itogiShown, icon = com.adamglin.PhosphorIcons.Regular.Sparkle) {
                    state.shown = false
                    itogi.shown = true
                }
            }
        }
        trailing()
    }
}

@Composable
private fun FairTab(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clip(RoundedCornerShape(8.dp)).then(Modifier.selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)).heightIn(min = 48.dp).padding(vertical = 4.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { androidx.compose.material3.Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
            Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        }
        Box(Modifier.padding(top = 4.dp).size(width = 28.dp, height = 2.dp).background(if (selected) color else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

/**
 * The fair copy of the note: what it is about, then the readable text, which can be selected, copied and shared from the bar above.
 * While Govorun works it says so; when it could not, it says why and offers the one screen of Govorun that settles it.
 */
@Composable
internal fun FairCard(
    note: NoteStore.Entry,
    state: FairState,
    actions: NotesActions,
    modifier: Modifier,
    tabs: (@Composable (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fair = state.of(note)
    val queue by com.govorun.echo.smart.FairQueue.changes.collectAsState()
    val waiting = remember(queue, note.id, state.making) { if (!state.making) com.govorun.echo.smart.FairQueue.entry(context, note.id) else null }
    val issue = rememberSmartIssue(waiting?.wait ?: state.failed, queue)
    val editing = state.editing
    LaunchedEffect(queue, note.id, editing) { state.refreshPreview(context, note) }
    val draft = state.draft
    LaunchedEffect(note.id, editing) {
        if (editing) androidx.compose.runtime.snapshotFlow { draft.text.toString() to state.busy }.collectLatest { (_, busy) ->
            if (!busy) { kotlinx.coroutines.delay(250); state.persist(context, note) }
        }
    }
    DisposableEffect(note.id, editing) { onDispose { state.persist(context, note) } }
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = ProductTokens.Radius.Card, topEnd = ProductTokens.Radius.Card))) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (editing) 16.dp else DOCK_CLEARANCE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) {
                tabs?.invoke {}
                NoteTextHeader(note, actions)
            }
            if (editing) when {
                state.making ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.fair_making), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FairProgress(state.run)
                    }
                waiting != null && !waiting.failed || issue != null -> {
                    if (isOfflineReason(issue)) NetworkNotice(issue!!)
                    else Text(if (waiting != null && !waiting.failed) fairWaiting(issue) else fairFailure(issue!!), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    issue?.let(::fairRemedy)?.let { (label, open) ->
                        TextButton(onClick = { scope.launch { open(context) } }) { Text(stringResource(label)) }
                    }
                }
            }
            if (state.saveFailed) Text(stringResource(R.string.fair_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (editing) {
                TextEditor(
                    draft,
                    autoFocus = true,
                    readOnly = state.busy,
                    onSave = { state.save(context, note, actions.onChanged) },
                    onCancel = { state.cancel(context, note) },
                    formatted = true,
                )
                EditorOriginalAction(note, state)
            }
            if (fair != null && !editing) {
                SelectionContainer { FairMarkdownText(fair.text) }
                if (note.mode != com.govorun.echo.notes.NoteText.MODE && !fair.fresh) Text(stringResource(R.string.fair_stale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun FairTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onClick: () -> Unit,
) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        androidx.compose.material3.Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(22.dp), tint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint)
    }
}

@Composable
private fun fairFailure(reason: String): String =
    when (reason) {
        GovorunSmart.TOO_LONG -> stringResource(R.string.fair_too_long)
        GovorunSmart.TOO_SHORT -> stringResource(R.string.fair_too_short)
        GovorunSmart.REJECTED_INPUT, GovorunSmart.MODEL_ANSWER, "model", "changed", GovorunSmart.SERVER_ERROR -> stringResource(R.string.fair_failed)
        else -> stringResource(smartStateText(reason).line)
    }

/** Why a note waits for its «Текст» when Govorun gave no words: it could not be asked yet, or it is on it. */
@Composable
private fun fairWaiting(wait: String?): String =
    when (wait) {
        null, GovorunSmart.QUEUED, "busy" -> stringResource(R.string.fair_wait_soon)
        else -> stringResource(smartStateText(wait).line)
    }

/** The one screen of Govorun that settles [reason], if there is one: its label and how to open it. */
internal fun fairRemedy(reason: String): Pair<Int, suspend (Context) -> Unit>? =
    run {
            val text = smartStateText(reason)
            if (text.action != null) text.action to { context -> openGovorunFor(context, reason); Unit } else null
    }

/** A missing style list needs its actual reason; compatible waiting states can still choose and queue a task. */
internal fun styleChoiceIssue(status: GovorunSmart.Status): String? = when {
    status.state == GovorunSmart.MISSING || status.state == GovorunSmart.UPDATE -> status.state
    status.styles.isNotEmpty() -> null
    status.state == GovorunSmart.READY -> "styles_missing"
    else -> status.state
}

/**
 * «Улучшить через Говорун»: how Govorun shapes this note — its own styles and the person's, the last one chosen first — and the one
 * button. A Govorun to be updated says so instead.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FairStyleSheet(
    note: NoteStore.Entry,
    state: FairState,
    actions: NotesActions,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var styles by androidx.compose.runtime.remember { mutableStateOf<GovorunSmart.Status?>(null) }
    var picked by androidx.compose.runtime.remember { mutableStateOf(com.govorun.echo.AppSettings.fairStyleToRun(context)) }
    var fromOriginal by remember { mutableStateOf(false) }
    val permission by Smart.changes.collectAsState()
    val enabled = remember(permission) { Smart.enabled() }
    androidx.lifecycle.compose.LifecycleResumeEffect(enabled) {
        val job = scope.launch {
            styles = null
            if (enabled) styles = withContext(Dispatchers.IO) {
                try { Smart.styleStatus(context) }
                catch (e: java.io.IOException) { GovorunSmart.Status((e as? GovorunSmart.Refusal)?.code ?: GovorunSmart.UNAVAILABLE) }
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    ProductModalSheet(
        onDismissRequest = { state.choosing = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.fair_action), style = MaterialTheme.typography.titleMedium)
            if (SmartNetworkNotice()) return@Column
            val status = styles ?: run {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                return@Column
            }
            val issue = styleChoiceIssue(status)
            if (issue != null) {
                NetworkNotice(issue)
                fairRemedy(issue)?.let { (label, open) ->
                    TextButton(onClick = { scope.launch { open(context) } }) { Text(stringResource(label)) }
                }
                return@Column
            }
            val list = status.styles
            // Govorun's styles as one row of chips, the one used last first; light cleanup is the default.
            val chosen = SmartFair.chooseStyle(picked, list)
            val last = com.govorun.echo.AppSettings.fairStyleToRun(context)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.sortedByDescending { it.value == last }.forEach { style ->
                    EchoChip(selected = style.value == chosen, onClick = { picked = style.value }) { Text(style.title) }
                }
            }
            if (note.mode != com.govorun.echo.notes.NoteText.MODE) {
                Row(Modifier.fillMaxWidth().toggleable(value = fromOriginal, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { fromOriginal = it }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.fair_from_original)); Text(stringResource(R.string.fair_from_original_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    androidx.compose.material3.Switch(checked = fromOriginal, onCheckedChange = null)
                }
            }
            if (isOfflineReason(status.state)) NetworkNotice(status.state)
            else androidx.compose.material3.Button(
                onClick = {
                    state.choosing = false
                    if (state.editing) state.improveEditor(context, note, actions.onChanged, chosen, fromOriginal)
                    else state.improve(context, note, actions.onChanged, chosen, if (fromOriginal) null else state.of(note)?.text)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = chosen != null,
            ) { Text(stringResource(R.string.fair_go)) }
        }
    }
}
