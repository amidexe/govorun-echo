package com.govorun.echo.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerSimpleLow
import com.govorun.echo.AppSettings
import com.govorun.echo.R

/**
 * A quiet swish when notes go to the trash or the archive, so the hand feels the note leave. Echo's own sounds (filtered noise,
 * made for it, no one else's licence), played low as the phone's touch sounds are, and never when the phone is on silent or
 * vibrate. Off until the person turns on «Звук удаления и архива» in Settings → Запись.
 */
internal object ActionSounds {
    val DELETE = R.raw.swish_delete
    val ARCHIVE = R.raw.swish_archive

    private const val VOLUME = 0.1f
    private var pool: SoundPool? = null
    private val loaded = mutableMapOf<Int, Int>()
    private val ready = mutableSetOf<Int>()
    private var waiting: Int? = null

    fun play(
        context: Context,
        sound: Int,
    ) {
        if (!AppSettings.actionSounds(context)) return
        if (context.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val pool = pool ?: open(context.applicationContext)
        val id = loaded.getOrPut(sound) { pool.load(context.applicationContext, sound, 1) }
        // The first time, the sound is still being read: it plays as soon as it is.
        if (id in ready) pool.play(id, VOLUME, VOLUME, 1, 0, 1f) else waiting = id
    }

    private fun open(context: Context): SoundPool =
        SoundPool
            .Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
            .also { pool ->
                pool.setOnLoadCompleteListener { p, id, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    ready += id
                    if (waiting == id) p.play(id, VOLUME, VOLUME, 1, 0, 1f).also { waiting = null }
                }
                this.pool = pool
                // Both are read now, so the archive's sound is ready by the time it is first wanted.
                listOf(DELETE, ARCHIVE).forEach { loaded.getOrPut(it) { pool.load(context, it, 1) } }
            }
}

/** «Тихие звуки»: the switch in Settings → Запись → «Звук и уведомления». */
@Composable
internal fun ActionSoundsSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.actionSounds(context)) }
    SwitchRow(PhosphorIcons.Regular.SpeakerSimpleLow, R.string.sounds_setting, R.string.sounds_setting_text, on) {
        on = it
        AppSettings.setActionSounds(context, it)
        if (it) ActionSounds.play(context, ActionSounds.DELETE)
    }
}
