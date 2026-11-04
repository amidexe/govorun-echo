package com.govorun.echo.smart

import android.content.Context
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Итоги being made, one per note at most, as [FairRuns] for fair copies: a run belongs to the app, not to a screen, and every try
 * settles the note's place in [FairQueue], whoever started it.
 */
internal object ItogiRuns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = java.util.concurrent.ConcurrentHashMap<String, Deferred<SmartItogi.Outcome>>()

    @Synchronized fun start(
        context: Context,
        note: NoteStore.Entry,
        asked: Boolean,
        onDone: () -> Unit = {},
    ): Deferred<SmartItogi.Outcome> {
        running(note.id)?.let { return it }
        val app = context.applicationContext
        val outcome =
            scope.async(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    val made = runCatching { SmartItogi.make(app, note, asked) }.getOrElse { SmartItogi.Outcome(null, GovorunSmart.SERVER_ERROR) }
                    FairQueue.settle(app, note.id, if (made.itogi != null) null else made.reason, FairQueue.ITOGI, made.taskId, made.wait, made.message)
                    // A local refusal can finish before TASKS_CHANGED reaches us. First record the hand-in, then read
                    // the task once so a notification that raced that record cannot leave the note waiting forever.
                    if (made.reason == GovorunSmart.QUEUED || made.reason == GovorunSmart.TIMEOUT) FairQueue.sync(app)
                    made
                }.also { onDone() }
            }
        runs[note.id] = outcome
        outcome.invokeOnCompletion { runs.remove(note.id, outcome) }
        outcome.start()
        return outcome
    }

    fun running(noteId: String): Deferred<SmartItogi.Outcome>? = runs[noteId]?.takeIf { it.isActive }
}

/**
 * A finished note waits in [FairQueue] for its итоги, unless «Итоги сами» is off or Govorun does not take a note so short or long.
 * Called for a new note, a continued one, a joined or split one and one whose sound was cut: its итоги follow its text. A new
 * note's итоги come at once; итоги made again wait until the note has rested [AGAIN_AFTER_MS], each change moving that on, so a
 * note continued or cut several times in a row is paid once. A change of a few words keeps its итоги fresh and asks nothing.
 */
fun itogiByItself(
    context: Context,
    noteId: String,
) {
    if (!com.govorun.echo.AppSettings.itogiAuto(context) || !Smart.chosen()) return
    val note = NoteStore.list(context).firstOrNull { it.id == noteId } ?: return
    // Govorun's local price check owns refusal too: silently skipping here would hide a size limit from the note.
    if (note.itogi?.fresh != true && note.state == NoteStore.STATE_DONE && note.text.isNotBlank()) {
        FairQueue.add(context, note.id, null, FairQueue.ITOGI, if (note.itogi != null) System.currentTimeMillis() + AGAIN_AFTER_MS else 0, asked = false)
    }
}

/** How long a changed note rests before its итоги are made again (Govorun's ask, 30 Oct 2026). */
const val AGAIN_AFTER_MS = 5 * 60_000L
