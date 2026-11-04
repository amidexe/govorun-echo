package com.govorun.echo.smart

import android.content.Context
import android.util.Log
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteItogi
import com.govorun.echo.notes.NoteStore
import org.json.JSONObject
import java.io.IOException

/**
 * Итоги: Govorun reads a note whole and gives its name, what it was about, things to do, decisions and open questions. Every
 * finished note Govorun takes gets them by itself (unless «Итоги сами» is off), whatever its length, with no question: итоги are
 * part of a note, as its transcript is (the owner, 30 Oct 2026). How short or long a note may be and whether the day is spent are Govorun's to say: Echo keeps only the bounds Govorun
 * last told it and shows nothing of the limit but a calm refusal.
 */
internal object SmartItogi {
    private const val TAG = "SmartItogi"

    @Volatile private var shortest = -1

    @Volatile private var longest = 0

    /** The bounds Govorun last told, kept across restarts so a short note is not offered итоги before Govorun is asked again. */
    private fun bounds(context: Context) {
        if (shortest < 0) AppSettings.itogiBounds(context).let { (min, max) -> shortest = min; longest = max }
    }

    private fun learn(context: Context, answer: JSONObject) {
        val min = answer.optInt("minChars")
        val max = answer.optInt("maxChars")
        if (min > 0 && max > 0 && (min != shortest || max != longest)) {
            shortest = min
            longest = max
            AppSettings.setItogiBounds(context, min, max)
        }
    }

    /** Whether «Подвести итоги» is offered for [note]: a build that talks to Govorun and a finished note within Govorun's bounds. */
    fun fits(
        context: Context,
        note: NoteStore.Entry,
    ): Boolean {
        bounds(context)
        val text = note.text.trim()
        val chars = text.codePointCount(0, text.length)
        return Smart.chosen() && note.state == NoteStore.STATE_DONE && chars > 0 &&
            (shortest <= 0 || chars >= shortest) && (longest == 0 || chars <= longest)
    }


    /** What Govorun says of итоги for a text before anything is sent: whether it takes a note so short or long. */
    class Price(
        val allowed: Boolean,
        val reason: String,
    )

    /** Asks Govorun the price of итоги for [note]. Throws [GovorunSmart.Refusal]. */
    suspend fun price(
        context: Context,
        note: NoteStore.Entry,
    ): Price {
        val smart = GovorunSmart(context)
        // Govorun's own first reason: signed out, offline, to be updated…
        val status = smart.status()
        if (status.state != GovorunSmart.READY) throw GovorunSmart.Refusal(status.state, 0)
        if (!status.capabilities.containsAll(setOf(GovorunSmart.TEXT_TASKS))) throw GovorunSmart.Refusal(GovorunSmart.UPDATE)
        val answer = smart.taskPrice("note.brief", note.text.trim(), "digest")
        learn(context, answer)
        return Price(
            allowed = answer.optBoolean("allowed"),
            reason = answer.optString("reason").takeUnless { answer.isNull("reason") }.orEmpty(),
        )
    }

    /** Retries share an id; a deliberate rebuild after a saved answer starts a new generation. The generation is part of the operation identity. */
    fun taskId(
        noteId: String,
        text: String,
        previousMadeAtMs: Long = 0,
    ): String =
        "itogi-" +
            java.security.MessageDigest.getInstance("SHA-256").digest(("note.brief\u0000$noteId\u0000$text" + if (previousMadeAtMs > 0) "\u0000$previousMadeAtMs" else "").toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }

    fun acceptedId(noteId: String, text: String, previousMadeAtMs: Long, held: String): Boolean =
        listOf(0L, previousMadeAtMs).any { held == taskId(noteId, text, it) }

    /** A try's end: the итоги, or why not; [taskId], [wait] and [message] — Govorun's task and its words when it keeps it. */
    class Outcome(
        val itogi: NoteItogi?,
        val reason: String = "",
        val taskId: String? = null,
        val wait: String? = null,
        val message: String = "",
    )

    /** Итоги of [note] from Govorun, kept beside it; [asked]: the person pressed «Итоги». Called on a background thread. */
    suspend fun make(
        context: Context,
        note: NoteStore.Entry,
        asked: Boolean,
    ): Outcome {
        val text = note.text.trim()
        val smart = GovorunSmart(context)
        if (!FairQueue.healthy(context)) return Outcome(null, "queue_storage")
        val held = FairQueue.entry(context, note.id, FairQueue.ITOGI)?.taskId
        if (held != null) {
            if (!acceptedId(note.id, text, note.itogi?.madeAtMs ?: 0, held)) return Outcome(null, "result_changed", held)
            val answer = try {
                val collected = JSONObject(smart.collect(held))
                JSONObject(collected.getString("text"))
            } catch (e: IOException) {
                val refusal = e as? GovorunSmart.Refusal
                return Outcome(null, refusal?.code ?: GovorunSmart.SERVER_ERROR, held, refusal?.wait, refusal?.words.orEmpty())
            }
            return save(context, note, answer)
        }
        if (!Smart.enabled()) return Outcome(null, Smart.disabledReason())
        // A Govorun not installed or to be updated is not asked: the note says so, and nothing is tried by itself.
        val status = smart.status()
        if (status.state == GovorunSmart.MISSING || status.state == GovorunSmart.UPDATE) return Outcome(null, status.state)
        if (!status.capabilities.containsAll(setOf(GovorunSmart.TEXT_TASKS))) return Outcome(null, status.state.takeUnless { it == GovorunSmart.READY } ?: GovorunSmart.UPDATE)
        // Govorun's bounds first, at once and with no network: a note outside them is not sent at all. A spent day is Govorun's
        // to wait out: it keeps the task and makes the итоги when the day turns.
        val price =
            try {
                smart.taskPrice("note.brief", text, "digest").also { learn(context, it) }
            } catch (e: IOException) {
                return Outcome(null, (e as? GovorunSmart.Refusal)?.code ?: GovorunSmart.SERVER_ERROR)
            }
        val reason = price.optString("reason").takeUnless { price.isNull("reason") }.orEmpty()
        if (!price.optBoolean("allowed", true) && (reason == GovorunSmart.TOO_SHORT || reason == GovorunSmart.TOO_LONG)) return Outcome(null, reason)
        val started = System.nanoTime()
        // Recollect an accepted task under its exact id, even if the note already has an older summary.
        val id = taskId(note.id, text, note.itogi?.madeAtMs ?: 0)
        if (!FairQueue.submitted(context, note.id, FairQueue.ITOGI, id)) return Outcome(null, "queue_storage")
        val answer: JSONObject =
            try {
                JSONObject(smart.textTask("note.brief", text, "digest", id, asked))
            } catch (e: IOException) {
                val refusal = e as? GovorunSmart.Refusal
                Log.w(TAG, "event=itogi_failed chars=${text.length} code=${refusal?.code} wait=${refusal?.wait}")
                return Outcome(null, refusal?.code ?: GovorunSmart.SERVER_ERROR, id, refusal?.wait, refusal?.words.orEmpty())
            }
        // The journal knows only the size and the time, never the text.
        Log.i(TAG, "event=itogi chars=${text.length} ms=${(System.nanoTime() - started) / 1_000_000}")
        return save(context, note, answer)
    }

    private fun save(context: Context, note: NoteStore.Entry, answer: JSONObject): Outcome {
        val itogi = NoteItogi.parse(answer)?.copy(madeAtMs = System.currentTimeMillis()) ?: return Outcome(null, "model")
        if (!NoteItogi.write(NoteStore.root(context), note.id, note.text, itogi)) return Outcome(null, "changed")
        return Outcome(itogi)
    }
}
