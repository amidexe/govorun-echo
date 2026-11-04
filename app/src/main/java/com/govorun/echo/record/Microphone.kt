package com.govorun.echo.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore

/** The microphone as a recording opens it, and the name of the way the sound comes in, for the journal. */
internal object Microphone {
    @SuppressLint("MissingPermission")
    fun open(bufferBytes: Int, input: android.media.AudioDeviceInfo): AudioRecord? {
        val rate = NoteStore.SAMPLE_RATE
        val minBuffer =
            AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC) // the same source as Govorun: about 19 dB louder on Pixel than VOICE_RECOGNITION, which sounded thin and quiet
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                ).setBufferSizeInBytes(minBuffer.coerceAtLeast(bufferBytes * 4))
                .build()
        } catch (_: RuntimeException) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return try {
            if (!record.setPreferredDevice(input)) {
                record.release()
                return null
            }
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record
            } else {
                record.release()
                null
            }
        } catch (error: Exception) {
            record.release()
            null
        }
    }

    fun routeName(
        context: Context,
        type: Int,
    ): String =
        context.getString(
            when (type) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.journal_route_builtin
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 -> R.string.journal_route_bluetooth
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> R.string.journal_route_wired
                else -> R.string.journal_route_other
            },
        )
}
