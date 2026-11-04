package com.govorun.echo.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Measures the loudness of the 16 kHz sound as it is recorded: one byte per [STEP_MS], the same bars the player draws. */
internal class LivePeaks(
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
) {
    private val filter = RumbleFilter(sampleRate)
    private val out = ByteArrayOutputStream()
    private val framesPerStep = sampleRate * STEP_MS / 1000
    private var frames = 0
    private var peak = 0

    /** One already filtered sample (the live meter filters the sound anyway, so nothing is computed twice while recording). */
    fun add(amplitude: Int) {
        peak = maxOf(peak, amplitude)
        if (++frames >= framesPerStep) {
            out.write(scale(peak))
            frames = 0
            peak = 0
        }
    }

    /** The same from raw 16-bit PCM, with its own filter. */
    fun feed(
        buffer: ByteArray,
        length: Int,
    ) {
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            add(abs(filter.process(sample.toShort().toFloat())).toInt())
            i += 2
        }
    }

    /** All the bars so far, the last unfinished step included. */
    fun bytes(): ByteArray {
        if (frames > 0) {
            out.write(scale(peak))
            frames = 0
            peak = 0
        }
        return out.toByteArray()
    }

    companion object {
        /** One bar per this many milliseconds of sound. */
        const val STEP_MS = 100

        /** Square root lifts quiet speech so it is visible next to loud peaks. */
        fun scale(peak: Int): Int = (sqrt(peak / 32768f) * 255f).toInt().coerceIn(0, 255)
    }
}
