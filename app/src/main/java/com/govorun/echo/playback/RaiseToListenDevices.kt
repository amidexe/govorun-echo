package com.govorun.echo.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** External outputs and active calls take priority, even while the sensor is covered. */
internal fun canRaiseToListen(mode: Int, outputs: List<Int>): Boolean =
    mode == AudioManager.MODE_NORMAL &&
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in outputs &&
        outputs.all {
            it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE ||
                it == AudioDeviceInfo.TYPE_TELEPHONY
        }
