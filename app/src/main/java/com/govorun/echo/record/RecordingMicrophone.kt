package com.govorun.echo.record

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.govorun.echo.AppSettings

/** Owns only this recording's input preference and its optional Bluetooth communication request.
 * Never changes global mode, volume or audio focus. Android's actual input is checked before any buffer is saved.
 */
internal class RecordingMicrophone(
    context: Context,
    preference: String,
    private val onFallback: () -> Unit,
) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private data class Candidate(val device: MicrophoneDevice, val input: AudioDeviceInfo?, val communication: AudioDeviceInfo? = null)
    @Volatile private var available = enumerate()
    private val choice = MicrophoneChoice(preference == AppSettings.MICROPHONE_HEADSET, available.map { it.device })
    @Volatile private var target = choice.current(available.map { it.device })
    @Volatile private var activeTarget: MicrophoneDevice? = null
    @Volatile private var activeInput: AudioDeviceInfo? = null
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var closed = false
    private var communicationOwned = false
    private var fallbackReported = false
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }
    private val modes = AudioManager.OnModeChangedListener {
        if (it != AudioManager.MODE_NORMAL && target?.type in BLUETOOTH) fallback() else refresh()
    }
    private val communication = AudioManager.OnCommunicationDeviceChangedListener { refresh() }

    init {
        audio.registerAudioDeviceCallback(devices, handler)
        audio.addOnModeChangedListener(context.mainExecutor, modes)
        audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, communication)
    }

    private fun enumerate(): List<Candidate> {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val mode = audio.mode
        val communication = if (mode == AudioManager.MODE_NORMAL) audio.availableCommunicationDevices else emptyList()
        val result = inputs.filter { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && MicrophoneChoice.usable(it.type, mode) }
            .map { Candidate(MicrophoneDevice(it.id, it.type), it) }.toMutableList()
        // SCO inputs may appear only after the link opens. Select a connected HFP sink, then wait for its actual source.
        communication.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }.forEach { sink ->
            result += Candidate(MicrophoneDevice(sink.id, sink.type), inputs.firstOrNull { matches(it, sink) }, sink)
        }
        return result
    }

    @Synchronized private fun refresh() {
        if (closed) return
        available = enumerate()
        val old = target
        target = choice.current(available.map { it.device })
        if (old != target) {
            clearCommunication()
            if (old?.phone == false && target?.phone == true) reportFallback()
            if (target?.phone == false) fallbackReported = false
        }
    }

    @Synchronized private fun fallback(failed: MicrophoneDevice? = target) {
        if (closed || target != failed) return
        val external = target?.phone == false
        clearCommunication()
        available = enumerate()
        target = choice.fallback(available.map { it.device })
        if (external && target?.phone == true) reportFallback()
    }

    private fun reportFallback() {
        if (!fallbackReported) {
            fallbackReported = true
            onFallback()
        }
    }

    @Synchronized private fun requestCommunication(device: AudioDeviceInfo): Boolean {
        if (closed || audio.mode != AudioManager.MODE_NORMAL) return false
        if (communicationOwned && audio.communicationDevice?.id == device.id) return true
        return runCatching { audio.setCommunicationDevice(device) }.getOrDefault(false).also { accepted ->
            if (accepted) communicationOwned = true
        }
    }

    @Synchronized private fun clearCommunication() {
        if (communicationOwned) {
            communicationOwned = false
            runCatching { audio.clearCommunicationDevice() }
        }
    }

    /** Called on the capture worker, including after audio-server errors. Never waits on the UI thread. */
    fun open(bufferBytes: Int, cancelled: () -> Boolean): AudioRecord? {
        while (!closed && !cancelled()) {
            refresh()
            val selected = target ?: return null
            val candidate = available.firstOrNull { it.device == selected } ?: continue
            var input = candidate.input
            val communication = candidate.communication
            if (communication != null) {
                if (!requestCommunication(communication)) {
                    fallback(selected)
                    continue
                }
                val deadline = SystemClock.elapsedRealtime() + BLUETOOTH_WAIT_MS
                while (!closed && !cancelled() && target == selected && SystemClock.elapsedRealtime() < deadline) {
                    input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { matches(it, communication) }
                    if (audio.communicationDevice?.id == communication.id && input != null) break
                    Thread.sleep(50)
                }
                if (closed || cancelled()) return null
                if (target != selected) continue
                if (audio.communicationDevice?.id != communication.id || input == null) {
                    fallback(selected)
                    continue
                }
            }
            if (input == null) {
                if (selected.phone) return null
                fallback(selected)
                continue
            }
            val record = Microphone.open(bufferBytes, input)
            if (record == null) {
                if (selected.phone) return null
                fallback(selected)
                continue
            }
            val deadline = SystemClock.elapsedRealtime() + INPUT_WAIT_MS
            while (!closed && !cancelled() && target == selected && !routedTo(record, input) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            if (!closed && !cancelled() && target == selected && routedTo(record, input)) {
                // Startup can include a transient system route: none of that queued sound belongs in the note.
                val discard = ByteArray(bufferBytes)
                var remaining = bufferBytes * 8
                while (remaining > 0) {
                    val read = record.read(discard, 0, discard.size, AudioRecord.READ_NON_BLOCKING)
                    if (read <= 0) break
                    remaining -= read
                }
                activeTarget = selected
                activeInput = input
                activeRecord = record
                return record
            }
            runCatching { record.stop() }
            record.release()
            if (closed || cancelled()) return null
            if (selected.phone && target == selected) return null
            fallback(selected)
        }
        return null
    }

    /** If Android overrules the preference, drop the buffer and reopen on the session's chosen input. */
    fun accepts(record: AudioRecord): Boolean =
        !closed && target == activeTarget && activeInput?.let { routedTo(record, it) } == true

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            clearCommunication()
            audio.unregisterAudioDeviceCallback(devices)
            audio.removeOnModeChangedListener(modes)
            audio.removeOnCommunicationDeviceChangedListener(communication)
        }
        runCatching { activeRecord?.stop() }
        activeRecord = null
    }

    companion object {
        private const val BLUETOOTH_WAIT_MS = 5_000L
        private const val INPUT_WAIT_MS = 1_500L
        private val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

        private fun matches(input: AudioDeviceInfo, output: AudioDeviceInfo): Boolean =
            input.type == output.type && (input.address == output.address || input.address.isEmpty() || output.address.isEmpty())

        private fun routedTo(record: AudioRecord, input: AudioDeviceInfo): Boolean =
            runCatching { record.routedDevice?.let { it.id == input.id || (input.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.type == input.type) } == true }.getOrDefault(false)
    }
}
