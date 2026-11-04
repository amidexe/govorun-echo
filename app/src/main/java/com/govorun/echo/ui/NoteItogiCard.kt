package com.govorun.echo.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.notes.NoteItogi
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.smart.FairQueue
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.ItogiRuns
import com.govorun.echo.smart.SmartItogi
import com.product.ui.ProductTokens
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Sparkle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Итоги» of the open note, a tab of its card beside «Расшифровка» and «Текст». A finished note gets them by itself through
 * [FairQueue]; the button makes them at once, with no question (the owner, 30 Oct 2026: the person spends the limit as they like),
 * after Govorun's price says the note fits. What waits, Govorun keeps and makes by itself (protocol 2); the tab shows Govorun's words
 * for it. A run belongs to the app ([ItogiRuns]): the tab only follows it.
 */
internal class ItogiState(
    private val scope: CoroutineScope,
) {
    /** The «Итоги» tab is the open one. */
    var shown by mutableStateOf(false)
    var making by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
    var made by mutableStateOf<NoteItogi?>(null)

    fun of(note: NoteStore.Entry): NoteItogi? = note.itogi?.takeIf { it.madeAtMs >= (made?.madeAtMs ?: Long.MIN_VALUE) } ?: made

    /** Whether the note's card has the «Итоги» tab: итоги made, being made, or a note Govorun makes them of. */
    fun tab(
        context: Context,
        note: NoteStore.Entry,
    ): Boolean = of(note) != null || making || failed != null || FairQueue.entry(context, note.id, FairQueue.ITOGI)?.failed == true ||
        (com.govorun.echo.smart.Smart.chosen() && note.state == NoteStore.STATE_DONE && note.text.isNotBlank())

    /** The button: fresh итоги open at once, otherwise Govorun is asked the price. */
    fun start(
        context: Context,
        note: NoteStore.Entry,
        onChanged: () -> Unit,
        again: Boolean = false,
    ) {
        shown = true
        if (!again && of(note)?.fresh == true) return
        if (!com.govorun.echo.smart.Smart.enabled()) { failed = com.govorun.echo.smart.Smart.disabledReason(); return }
        scope.launch {
            val price =
                try {
                    withContext(Dispatchers.IO) { SmartItogi.price(context, note) }
                } catch (e: java.io.IOException) {
                    val refusal = e as? GovorunSmart.Refusal
                    failed = refusal?.code ?: GovorunSmart.UNAVAILABLE
                    FairQueue.refuse(context, note.id, checkNotNull(failed), FairQueue.ITOGI, refusal?.words.orEmpty())
                    return@launch
                }
            if (price.allowed) run(context, note, onChanged) else {
                failed = price.reason
                FairQueue.refuse(context, note.id, price.reason, FairQueue.ITOGI)
            }
        }
    }

    fun run(
        context: Context,
        note: NoteStore.Entry,
        onChanged: () -> Unit,
    ) {
        shown = true
        // The note waits in the queue until Govorun gives its итоги; whatever stops them, Govorun waits out itself.
        if (!FairQueue.add(context, note.id, null, FairQueue.ITOGI)) {
            failed = if (com.govorun.echo.smart.Smart.enabled()) "queue_storage" else com.govorun.echo.smart.Smart.disabledReason()
            return
        }
        follow(ItogiRuns.start(context, note, true, onChanged))
    }

    /** Shows [run] on this screen until it ends; the run itself goes on if the screen goes. */
    fun follow(run: Deferred<SmartItogi.Outcome>) {
        making = true
        failed = null
        scope.launch {
            val outcome = run.await()
            making = false
            if (outcome.itogi != null) made = outcome.itogi else failed = outcome.reason.takeUnless { it in FairQueue.KEPT }
        }
    }

}

@Composable
internal fun rememberItogiState(noteId: String): ItogiState {
    val scope = rememberCoroutineScope()
    return remember(noteId) { ItogiState(scope).also { state -> ItogiRuns.running(noteId)?.let { state.follow(it) } } }
}

/**
 * The «Кратко» tab: a name, one sentence and one complete bulleted list for any kind of recording. Before it: why
 * they wait, calmly, or the button. Copy and share take it as Markdown from the screen's bar.
 */
@Composable
internal fun ItogiCard(
    note: NoteStore.Entry,
    state: ItogiState,
    onChanged: () -> Unit,
    modifier: Modifier,
    tabs: (@Composable (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) -> Unit)?,
) {
    val context = LocalContext.current
    val itogi = state.of(note)
    val queue by FairQueue.changes.collectAsState()
    val waiting = remember(queue, note.id, state.making, itogi) { if (!state.making) FairQueue.entry(context, note.id, FairQueue.ITOGI) else null }
    val issue = rememberSmartIssue(waiting?.wait ?: state.failed, queue)
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = ProductTokens.Radius.Card, topEnd = ProductTokens.Radius.Card))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = DOCK_CLEARANCE), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            tabs?.invoke {}
            val calm = MaterialTheme.typography.bodyMedium
            val grey = MaterialTheme.colorScheme.onSurfaceVariant
            val permission by com.govorun.echo.smart.Smart.changes.collectAsState()
            val enabled = remember(permission) { com.govorun.echo.smart.Smart.enabled() }
            val offline = (if (!enabled) com.govorun.echo.smart.Smart.disabledReason() else issue)
                ?.takeIf(::isOfflineReason)
            androidx.lifecycle.compose.LifecycleResumeEffect(state) {
                onPauseOrDispose { if (isOfflineReason(state.failed)) state.failed = null }
            }
            // A previous failed attempt does not keep a local restriction after the person allows the network again.
            LaunchedEffect(permission) {
                if (enabled && state.failed in setOf(com.govorun.echo.NetworkGate.OFFLINE, com.govorun.echo.NetworkGate.ROAMING)) state.failed = null
            }
            if (itogi != null) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (itogi.nazvanie.isNotBlank()) Text(itogi.nazvanie, style = MaterialTheme.typography.titleLarge)
                            if (itogi.oChem.isNotBlank()) Text(itogi.oChem, style = MaterialTheme.typography.bodyLarge, color = grey)
                        }
                        if (itogi.content.isNotBlank()) FairMarkdownText(itogi.content)
                    }
                }
                Text(stringResource(R.string.itogi_by_govorun), style = MaterialTheme.typography.bodySmall, color = grey)
                if (!itogi.fresh) Text(stringResource(R.string.itogi_stale), style = MaterialTheme.typography.bodySmall, color = grey)
            }
            // Existing content stays first; the last attempt only changes its footer action.
            when {
                state.making -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.itogi_making), style = calm, color = grey)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                offline != null -> if (itogi == null || state.failed != null || waiting != null) NetworkNotice(offline)
                else -> {
                    if (issue != null) {
                        Text(if (waiting != null && !waiting.failed) itogiWaiting(issue) else itogiFailure(issue), style = calm, color = grey)
                    } else if (waiting != null && !waiting.failed) {
                        Text(itogiWaiting(null), style = calm, color = grey)
                    } else if (itogi == null) {
                        Text(stringResource(R.string.itogi_empty), style = calm, color = grey)
                    }
                    if (SmartItogi.fits(context, note)) {
                        val label = if (itogi == null) R.string.itogi_go else if (itogi.fresh) R.string.itogi_rebuild else R.string.itogi_again
                        ItogiButton(label) { state.start(context, note, onChanged, again = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItogiButton(label: Int, onClick: () -> Unit) =
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        Icon(PhosphorIcons.Regular.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), modifier = Modifier.padding(start = 6.dp))
    }

/** Why a note waits for its итоги when Govorun gave no words: it could not be asked yet, or it is on them. */
@Composable
private fun itogiWaiting(wait: String?): String =
    when (wait) {
        null, GovorunSmart.QUEUED, "busy" -> stringResource(R.string.itogi_wait_soon)
        else -> stringResource(smartStateText(wait).line)
    }

@Composable
private fun itogiFailure(reason: String): String =
    when (reason) {
        GovorunSmart.TOO_SHORT -> stringResource(R.string.itogi_too_short)
        GovorunSmart.TOO_LONG -> stringResource(R.string.itogi_too_long)
        FairQueue.MODEL, FairQueue.CHANGED, GovorunSmart.MODEL_ANSWER, GovorunSmart.REJECTED_INPUT, GovorunSmart.SERVER_ERROR -> stringResource(R.string.itogi_failed)
        else -> stringResource(smartStateText(reason).line)
    }
