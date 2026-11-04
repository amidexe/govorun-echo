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
 * Fair copies being made, one per note at most. A run belongs to the app, not to a screen: closing the style sheet or the note does
 * not cancel it (Govorun has already been asked and spends its limit), and a second tap on the same note joins the run under way.
 * When it ends the notes are read again, so the fair copy is there wherever the note is opened next.
 */
internal object FairRuns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = java.util.concurrent.ConcurrentHashMap<String, Run>()

    /** A run and what its bar needs: when it began and how long Govorun is expected to take. */
    class Run(
        val outcome: Deferred<SmartFair.Outcome>,
        val startedAtMs: Long,
        val expectedMs: Long,
        val draftId: String? = null,
    )

    @Synchronized fun start(
        context: Context,
        note: NoteStore.Entry,
        style: String?,
        onDone: () -> Unit,
        source: String? = null,
        asked: Boolean = true,
        draftId: String? = null,
    ): Run {
        running(note.id)?.takeIf { it.draftId == draftId }?.let { return it }
        val app = context.applicationContext
        val outcome =
            scope.async(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    // Every try settles the note's place in the queue, whoever started it.
                    val made = runCatching { SmartFair.make(app, note, style, source, asked, draftId) }.getOrElse { SmartFair.Outcome(null, GovorunSmart.SERVER_ERROR) }
                    FairQueue.settle(app, note.id, if (made.fair != null) null else made.reason, FairQueue.FAIR, made.taskId, made.wait, made.message, draftId)
                    if (made.reason == GovorunSmart.QUEUED || made.reason == GovorunSmart.TIMEOUT) FairQueue.sync(app)
                    made
                }.also { onDone() }
            }
        val run = Run(outcome, android.os.SystemClock.elapsedRealtime(), expectedMs((source ?: note.text).trim().length), draftId)
        runs[note.id] = run
        outcome.invokeOnCompletion { runs.remove(note.id, run) }
        outcome.start()
        return run
    }

    fun running(noteId: String): Run? = runs[noteId]?.takeIf { it.outcome.isActive }

    /**
     * Govorun's model rewrites about 500 characters a second; a short note still takes a few seconds there and back, and the
     * longest note Govorun takes (10 000 characters) about 20 s.
     */
    fun expectedMs(chars: Int): Long = (chars * 2L).coerceIn(3_000L, 30_000L)
}
