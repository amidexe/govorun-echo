package com.govorun.echo.record

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.govorun.echo.Journal
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.recognition.LiveSession
import java.io.File

/** Owns the live preview for one recording. The PCM file remains the source for an interrupted stream. */
internal class CaptureRecognition(private val context: Context, private val dir: File) {
    val speech = CaptureSpeech().also { it.reset() }

    @Volatile var session: LiveSession? = null
        private set
    @Volatile var dictating = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var carry = ""
    private var micSilenced = false
    private var closed = false
    private var retryDelay = FIRST_RETRY_MS
    private val retry = Runnable { retryStream() }

    @Synchronized
    fun start() {
        if (!closed && session == null) connect(interim = false)
    }

    @Synchronized
    fun microphoneSilenced(silenced: Boolean) {
        if (closed || micSilenced == silenced) return
        micSilenced = silenced
        handler.removeCallbacks(retry)
        if (silenced) {
            val old = retireStream()
            old?.cancel()
            Journal.add(context, Journal.RECOGNITION, context.getString(R.string.journal_live_yielded))
        } else {
            connect(interim = true)
        }
    }

    /** Detach before saving so a pending retry or a late callback cannot touch the note. */
    @Synchronized
    fun detach(): LiveSession? {
        closed = true
        handler.removeCallbacks(retry)
        generation++
        return session.also { session = null }
    }

    @Synchronized
    private fun retryStream() {
        if (closed || micSilenced || session?.failed != true) return
        retireStream()
        connect(interim = true)
    }

    private fun retireStream(): LiveSession? {
        generation++
        val old = session
        carry = joined(old?.textSoFar().orEmpty())
        session = null
        dictating = false
        speech.interrupted()
        return old
    }

    private fun connect(interim: Boolean) {
        val current = ++generation
        val stream = LiveSession(
            context,
            onText = { text ->
                forStream(current) {
                    NoteStore.writeLiveText(dir, joined(text))
                    RecordingBus.liveTextArrived()
                }
            },
            onSpeech = { active -> forStream(current) { speech.changed(active) } },
            onDictation = { active -> forStream(current) { dictating = active } },
            onReady = {
                forStream(current) {
                    speech.ready()
                    if (interim) Journal.add(context, Journal.RECOGNITION, context.getString(R.string.journal_live_back))
                    RecordingBus.liveProblem.value = null
                }
            },
            onFailed = { reason -> handler.post { forStream(current) { failed(reason) } } },
            interimOnly = interim,
        )
        session = stream
        stream.start()
    }

    @Synchronized
    private fun forStream(current: Int, action: () -> Unit) {
        if (!closed && current == generation) action()
    }

    private fun failed(reason: String) {
        dictating = false
        speech.interrupted()
        RecordingBus.liveProblem.value = reason
        if (reason in PERMANENT_FAILURES || micSilenced) return
        handler.postDelayed(retry, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun joined(text: String): String = listOf(carry, text).filter { it.isNotBlank() }.joinToString(" ")

    private companion object {
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 8_000L
        val PERMANENT_FAILURES = setOf(
            "cancelled", "not_installed", "unauthorized", "protocol", "no_continuous",
            "govorun_1", "govorun_3", "govorun_4", "govorun_6", "govorun_8",
        )
    }
}
