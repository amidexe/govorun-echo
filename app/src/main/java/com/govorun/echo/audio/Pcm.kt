package com.govorun.echo.audio

/** The one sound format Echo works in: 16 kHz, 16-bit mono PCM, what the microphone gives and Govorun takes. */
internal object Pcm {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    fun durationMs(samples: Int): Long = samples * 1_000L / SAMPLE_RATE
}
