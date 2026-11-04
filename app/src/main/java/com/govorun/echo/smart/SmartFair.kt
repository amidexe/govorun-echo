package com.govorun.echo.smart

import android.content.Context
import android.util.Log
import com.govorun.echo.notes.NoteFair
import com.govorun.echo.notes.NoteStore
import java.io.IOException

/**
 * «Улучшить через Говорун»: the note's text through Govorun's own text improvement, with its limits, consent, «Офлайн» and the
 * style the person set for Echo in Govorun. The fair copy is kept beside the transcript ([NoteFair]), never instead of it. How short or
 * long a note may be is Govorun's to say (its plan and tariff decide, and may change): Echo keeps no number of its own, only what
 * Govorun last told it. Until it has told, every note is offered and Govorun's own answer is shown. Called on a background thread.
 */
internal object SmartFair {
    /** The bounds Govorun last reported, 0 for «not said yet». */
    @Volatile private var shortest = 0

    @Volatile private var longest = 0

    fun learn(
        min: Int,
        max: Int,
    ) {
        shortest = min
        longest = max
    }

    fun tooLong(note: NoteStore.Entry): Boolean = tooLong(note.text.trim().length)

    fun tooShort(note: NoteStore.Entry): Boolean = tooShort(note.text.trim().length)

    fun tooLong(chars: Int): Boolean = longest > 0 && chars > longest

    fun tooShort(chars: Int): Boolean = shortest > 0 && chars < shortest
    private const val TAG = "SmartFair"

    /** A try's end: the «Текст», or why not; [taskId], [wait] and [message] — Govorun's task and its words when it keeps it. */
    class Outcome(
        val fair: NoteFair?,
        val reason: String = "",
        val taskId: String? = null,
        val wait: String? = null,
        val message: String = "",
    )

    /**
     * The same note, style and text ask Govorun under one task id, so asking again — a second tap, Echo reopened after it was
     * closed — joins Govorun's run or gets the text Govorun remembers, and the note is paid for once.
     */
    fun taskId(noteId: String, style: String?, text: String): String =
        "note-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("text.improve\u0000$noteId\u0000${style.orEmpty()}\u0000$text".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    fun matchesTask(noteId: String, style: String?, text: String, held: String): Boolean =
        listOf(style, "light").distinct().any { held == taskId(noteId, it, text) }

    /** Whether [note] may get a fair copy: done, and its text within Govorun's bounds. */
    fun fits(note: NoteStore.Entry): Boolean = note.state == NoteStore.STATE_DONE && note.text.isNotBlank() && !tooLong(note) && !tooShort(note)

    /** Null deliberately selects the immutable transcript, including queued retries. Typed notes have one living text. */
    fun editorSource(mode: String, current: String, fromOriginal: Boolean): String? =
        if (fromOriginal && mode != com.govorun.echo.notes.NoteText.MODE) null else current

    /** Respect an explicit style; otherwise clean the transcript without asking for note metadata. */
    fun chooseStyle(requested: String?, known: List<GovorunSmart.Style>): String? =
        requested?.takeIf { value -> known.any { it.value == value } }
            ?: known.firstOrNull { it.value == "light" }?.value
            ?: known.firstOrNull()?.value

    fun make(
        context: Context,
        note: NoteStore.Entry,
        style: String?,
        /** What to improve: the open «Текст» as it stands; the note's own text when null (the original). */
        source: String? = null,
        /** The person pressed «Улучшить». */
        asked: Boolean = true,
        draftId: String? = null,
    ): Outcome {
        val text = (source ?: note.text).trim()
        if (!FairQueue.healthy(context)) return Outcome(null, "queue_storage")
        val held = FairQueue.entry(context, note.id)?.taskId
        if (held != null) {
            if (!matchesTask(note.id, style, text, held)) return Outcome(null, "result_changed", held)
            val reply = try {
                org.json.JSONObject(kotlinx.coroutines.runBlocking { GovorunSmart(context).collect(held) }).getString("text")
            } catch (e: IOException) {
                val refusal = e as? GovorunSmart.Refusal
                return Outcome(null, refusal?.code ?: GovorunSmart.SERVER_ERROR, held, refusal?.wait, refusal?.words.orEmpty())
            }
            return save(context, note, text, reply, draftId)
        }
        if (!Smart.enabled()) return Outcome(null, Smart.disabledReason())
        // A chosen style is retained; the default cleans the transcript instead of making another summary.
        // Never left to «Стили для приложений», which may hold a translation or anything else the person set for another job.
        var unavailable = GovorunSmart.UNAVAILABLE
        val known =
            if (Smart.stand != null) {
                runCatching { kotlinx.coroutines.runBlocking { Smart.styleStatus(context) } }.getOrNull()?.styles.orEmpty()
            } else {
                val status = runCatching { kotlinx.coroutines.runBlocking { GovorunSmart(context).status() } }.getOrNull()
                unavailable = status?.state?.takeUnless { it == GovorunSmart.READY } ?: GovorunSmart.UNAVAILABLE
                if (status?.state == GovorunSmart.UPDATE) return Outcome(null, GovorunSmart.UPDATE)
                if (status?.capabilities?.isNotEmpty() == true && !status.capabilities.containsAll(setOf(GovorunSmart.TEXT_TASKS))) return Outcome(null, GovorunSmart.UPDATE)
                status?.styles.orEmpty()
            }
        val style = chooseStyle(style, known) ?: return Outcome(null, unavailable)
        if (tooLong(text.length)) return Outcome(null, GovorunSmart.TOO_LONG)
        if (tooShort(text.length)) return Outcome(null, GovorunSmart.TOO_SHORT)
        // How long Govorun took, for the journal of a slow fair copy: never the text, only its size and the style.
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val id = taskId(note.id, style, text)
        if (!FairQueue.submitted(context, note.id, FairQueue.FAIR, id)) return Outcome(null, "queue_storage")
        val reply =
            try {
                Smart.improver(context)(id, text, style, asked)
            } catch (e: IOException) {
                val refusal = e as? GovorunSmart.Refusal
                Log.w(TAG, "event=improve_failed chars=${text.length} style=$style code=${refusal?.code} wait=${refusal?.wait} ms=${ms()}")
                return Outcome(null, refusal?.code ?: GovorunSmart.SERVER_ERROR, id, refusal?.wait, refusal?.words.orEmpty())
            }
        Log.i(TAG, "event=improved chars=${text.length} reply=${reply.length} style=$style ms=${ms()}")
        return save(context, note, text, reply, draftId)
    }

    private fun save(context: Context, note: NoteStore.Entry, text: String, reply: String, draftId: String?): Outcome {
        val fair = NoteFair.prepared(reply, System.currentTimeMillis(), textOnly = true) ?: return Outcome(null, "model")
        if (draftId != null) return if (com.govorun.echo.notes.NoteEditDraft.finish(NoteStore.root(context), note.id, draftId, note.text, fair.text)) Outcome(fair) else Outcome(null, FairQueue.GONE)
        if (!NoteFair.write(NoteStore.root(context), note.id, if (note.mode == com.govorun.echo.notes.NoteText.MODE) text else note.text, fair)) return Outcome(null, "changed")
        return Outcome(fair)
    }
}
