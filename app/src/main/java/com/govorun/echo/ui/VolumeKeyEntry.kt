package com.govorun.echo.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.recognition.GovorunProvider
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Govorun's volume key on the lock screen: a hold opens this over the lock screen to start a note, the release opens it again to
 * stop. While «−» is held it stays, keeps the screen on and shows Echo's card «Записываю заметку» with the voice's level, so a dark
 * screen does not look like a stopped recording; the release turns it into «Заметка сохранена» for a moment. Only Govorun can open
 * it: the manifest requires Govorun's signature permission, and the permission must belong to the real Govorun Echo trusts.
 */
class VolumeKeyEntry : ComponentActivity() {
    private enum class Phase { Recording, Saving, Saved, Empty }

    private var phase by mutableStateOf(Phase.Recording)
    private var watch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val event = intent?.getStringExtra(EXTRA_EVENT)
        if (!fromGovorun()) {
            Log.w(TAG, "event=volume_key_refused")
            finishAndRemoveTask()
            return
        }
        Log.i(TAG, "event=volume_key_$event")
        val sent =
            runCatching {
                when (event) {
                    START -> startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_NOTE))
                    STOP -> startService(RecordingService.stopIntent(this))
                }
            }.onFailure { Log.w(TAG, "event=volume_key_failed", it) }.isSuccess
        when {
            !sent -> finishAndRemoveTask()
            event == START -> hold()
            event == STOP && watch != null -> phase = Phase.Saving
            else -> finishAndRemoveTask()
        }
    }

    /** While «−» is held: the screen stays on with Echo's card; it goes when the recording ends, however it ends. */
    private fun hold() {
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phase = Phase.Recording
        setContent { EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this)) { HoldCard(phase) } }
        watch?.cancel()
        watch =
            lifecycleScope.launch {
                // Android may refuse the microphone: then there is nothing to hold the screen for.
                if (RecordingBus.state.first { it is RecordingState.Recording || it is RecordingState.Failed } is RecordingState.Recording) {
                    AppSettings.countVolumeKeyHold(this@VolumeKeyEntry)
                    val end = RecordingBus.state.first { it !is RecordingState.Recording && it !is RecordingState.Starting }
                    if (end is RecordingState.Saved) {
                        // A slip of the key — a few seconds, no word heard — is not called saved: it goes to the trash once checked.
                        val live = runCatching { java.io.File(com.govorun.echo.notes.NoteStore.root(this@VolumeKeyEntry), end.noteId + "/live.txt").readText() }.getOrDefault("")
                        val empty = AppSettings.dropEmpty(this@VolumeKeyEntry) && end.durationMs < com.govorun.echo.notes.EmptyNotes.MAX_MS && live.isBlank()
                        phase = if (empty) Phase.Empty else Phase.Saved
                        delay(SAVED_SHOWN_MS)
                    }
                }
                finishAndRemoveTask()
            }
        // A note by the key is short; should no release ever come, the card does not stay for good.
        lifecycleScope.launch {
            delay(HOLD_LIMIT_MS)
            finishAndRemoveTask()
        }
    }

    private fun fromGovorun(): Boolean {
        val govorun = GovorunProvider.resolve(this)?.packageName ?: return false
        val owner = runCatching { packageManager.getPermissionInfo("$govorun.permission.VOLUME_KEY", 0).packageName }.getOrNull()
        return owner == govorun
    }

    /**
     * A card over the lock screen: «Записываю заметку» over slow, even waves, so a held key shows the recording goes on. How to save
     * is told only the first few times.
     */
    @Composable
    private fun HoldCard(phase: Phase) {
        val level by RecordingBus.level.collectAsState()
        val explain = remember { AppSettings.volumeKeyHolds(this) < EXPLAIN_HOLDS }
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Box {
                    VoiceFlow(Modifier.matchParentSize())
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedContent(phase, label = "hold") { shown ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (shown == Phase.Saved) {
                                    Icon(PhosphorIcons.Regular.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                } else if (shown == Phase.Recording) {
                                    LevelBars(level)
                                }
                                Text(stringResource(when (shown) { Phase.Saved -> R.string.volume_key_saved; Phase.Empty -> R.string.volume_key_empty; Phase.Saving -> R.string.volume_key_saving; else -> R.string.volume_key_recording }), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (explain && phase == Phase.Recording) Text(stringResource(R.string.volume_key_release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    /** Three small bars that follow the voice, as the recording dot in Echo's notification does. */
    @Composable
    private fun LevelBars(level: Float) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(0.6f, 1f, 0.75f).forEach { k ->
                Box(Modifier.width(4.dp).height((6 + 16 * (level * k).coerceIn(0f, 1f)).dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            }
        }
    }

    private companion object {
        const val TAG = "VolumeKeyEntry"
        const val EXTRA_EVENT = "com.govorun.api.extra.VOLUME_KEY_EVENT"
        const val START = "start"
        const val STOP = "stop"
        const val SAVED_SHOWN_MS = 1_200L
        const val HOLD_LIMIT_MS = 10 * 60_000L
        const val EXPLAIN_HOLDS = 3
    }
}
