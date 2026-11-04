package com.govorun.echo.record

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A session's stable device identity; a Bluetooth communication sink can represent its matching input. */
internal data class MicrophoneDevice(val id: Int, val type: Int) {
    val phone: Boolean get() = type == AudioDeviceInfo.TYPE_BUILTIN_MIC
}

/** Keep a working headset, use the phone while it is absent, and return when a headset connects.
 * A rejected device is skipped until it disconnects, so an unsuccessful route cannot cause a reopen loop.
 */
internal class MicrophoneChoice(private val headset: Boolean, available: List<MicrophoneDevice>) {
    private var selected: MicrophoneDevice? = null
    private val rejected = mutableSetOf<MicrophoneDevice>()

    init { current(available) }

    @Synchronized
    fun current(available: List<MicrophoneDevice>): MicrophoneDevice? {
        rejected.retainAll(available.toSet())
        // Connecting another device does not interrupt a headset that is still working.
        if (headset && selected?.phone == false && selected in available && selected !in rejected) return selected
        selected = available.filter { priority(it.type) != null && (headset || it.phone) && it !in rejected }
            .minWithOrNull(compareBy({ priority(it.type) }, { it.id }))
        return selected
    }

    @Synchronized
    fun fallback(available: List<MicrophoneDevice>): MicrophoneDevice? {
        selected?.takeUnless { it.phone }?.let { rejected += it }
        selected = null
        return current(available)
    }

    companion object {
        fun usable(type: Int, mode: Int): Boolean = priority(type) != null &&
            (mode == AudioManager.MODE_NORMAL || type !in setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        fun priority(type: Int): Int? = when (type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> 0
            AudioDeviceInfo.TYPE_USB_DEVICE -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 3
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> 5
            else -> null // A2DP, headphones without a mic, hearing aids and unrelated inputs are not microphones we select.
        }
    }
}
