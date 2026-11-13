package com.govorun.echo.record

import com.govorun.echo.notes.NoteStore

/**
 * Why a recording ends by itself: the phone is nearly out of battery or out of room, or (when the person chose it) Govorun's
 * speech detector has heard nothing for that long. Silence never ends it by default rule alone: the person picks the time or
 * «Никогда», because quiet speech far from the phone can go unheard, and a forgotten recording costs only space they can see.
 */
internal object StopPolicy {
    const val MIN_BATTERY_PERCENT = 3
    const val MIN_FREE_BYTES = NoteStore.MIN_FREE_BYTES

    /** How long before a silence stop the person is told, so a tap on «Продолжить» keeps the recording going. */
    const val SILENCE_WARNING_MS = 60_000L

    fun commandReason(heardSpeech: Boolean, idleMs: Long): String? = when {
        heardSpeech && idleMs >= 1_800L -> RecordingService.STOP_COMMAND_PAUSE
        !heardSpeech && idleMs >= 5_000L -> RecordingService.STOP_COMMAND_NO_SPEECH
        else -> null
    }

    @Suppress("UNUSED_PARAMETER")
    fun reason(
        mode: String,
        idleMs: Long,
        batteryPercent: Int,
        charging: Boolean,
        freeBytes: Long,
        silenceLimitMs: Long = 0L,
    ): String? =
        when {
            freeBytes < MIN_FREE_BYTES -> RecordingService.STOP_STORAGE
            batteryPercent in 0 until MIN_BATTERY_PERCENT && !charging -> RecordingService.STOP_BATTERY
            silenceLimitMs > 0 && idleMs >= silenceLimitMs -> RecordingService.STOP_SILENCE
            else -> null
        }

    /** The recording will stop for silence within a minute: time to tell the person. */
    fun warnsOfSilence(
        idleMs: Long,
        silenceLimitMs: Long,
    ): Boolean = silenceLimitMs > 0 && idleMs >= silenceLimitMs - SILENCE_WARNING_MS
}
