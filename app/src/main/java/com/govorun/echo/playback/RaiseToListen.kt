package com.govorun.echo.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.govorun.echo.AppSettings

/** Only this player's AudioTrack gets a preferred device. Calls, Bluetooth and system audio mode are never changed. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RaiseToListen(
    private val context: Context,
    private val route: (AudioDeviceInfo?) -> Boolean,
    rewind: () -> Unit,
) : SensorEventListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock =
        if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "govorun.echo:listen-at-ear").apply { setReferenceCounted(false) }
        } else null
    private var playing = false
    private var watching = false
    private var preferred: AudioDeviceInfo? = null
    private val session: RaiseToListenSession =
        RaiseToListenSession(
            object : RaiseToListenSession.Output {
                override fun monitor(on: Boolean): Boolean =
                    if (on) {
                        sensors.registerListener(this@RaiseToListen, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                    } else {
                        sensors.unregisterListener(this@RaiseToListen)
                        true
                    }

                override fun enterEar(): Boolean {
                    val ear = earpiece() ?: return false
                    if (!safeOutput() || !route(ear)) return false
                    preferred = ear
                    return try {
                        wakeLock?.acquire()
                        Log.i(TAG, "event=earpiece")
                        true
                    } catch (_: RuntimeException) {
                        leaveEar()
                        false
                    }
                }

                override fun leaveEar() {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                    preferred = null
                    route(null)
                    Log.i(TAG, "event=default_output")
                }

                override fun rewind() = rewind.invoke()
            },
        )
    private val devices =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
    private val modeChanged = AudioManager.OnModeChangedListener { refresh() }

    fun playing(value: Boolean) {
        playing = value
        refresh()
    }

    fun refresh() {
        val watch = playing && AppSettings.raiseToListen(context) && sensor != null && wakeLock != null
        if (watch != watching) {
            watching = watch
            if (watch) {
                audio.registerAudioDeviceCallback(devices, handler)
                audio.addOnModeChangedListener(context.mainExecutor, modeChanged)
            } else {
                audio.unregisterAudioDeviceCallback(devices)
                audio.removeOnModeChangedListener(modeChanged)
            }
        }
        session.eligible(watch && safeOutput() && earpiece() != null)
    }

    /** A new note or the next note can keep the route without briefly playing through the loudspeaker. */
    fun applyTo(player: ExoPlayer) = player.setPreferredAudioDevice(preferred)

    override fun onSensorChanged(event: SensorEvent) {
        // Binary sensors use maximumRange for far; distance sensors can have a much larger range.
        session.proximity(event.values[0] < minOf(event.sensor.maximumRange, 5f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun earpiece() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    private fun safeOutput(): Boolean = canRaiseToListen(audio.mode, audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })

    private companion object {
        const val TAG = "RaiseToListen"
    }
}
