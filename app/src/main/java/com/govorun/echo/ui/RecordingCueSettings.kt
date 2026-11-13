package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Vibrate
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import kotlinx.coroutines.launch
import com.product.ui.ProductChoiceSheet
import com.product.ui.SettingsLinkRow

/** «Остановить, если долго тихо»: never, or after half an hour, an hour (the default) or two hours without speech. */
@Composable
internal fun SilenceStopSetting() {
    val context = LocalContext.current
    var value by remember { mutableStateOf(AppSettings.silenceStopMinutes(context)) }
    var open by remember { mutableStateOf(false) }
    fun label(minutes: Int) =
        when {
            minutes == 0 -> context.getString(R.string.settings_silence_never)
            minutes % 60 != 0 -> context.resources.getQuantityString(R.plurals.minutes, minutes, minutes)
            else -> context.resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)
        }
    SettingsLinkRow(
        PhosphorIcons.Regular.Timer,
        stringResource(R.string.settings_silence_title),
        value = label(value),
        onClick = { open = true },
    )
    if (open) {
        ProductChoiceSheet(
            title = stringResource(R.string.settings_silence_title),
            selected = value,
            options = AppSettings.SILENCE_STOP_MINUTES,
            optionLabel = ::label,
            onSelected = { value = it; AppSettings.setSilenceStopMinutes(context, it) },
            onDismissRequest = { open = false },
        )
    }
}

/** The taps when a recording starts and is saved, and the one before a silence stop. */
@Composable
internal fun RecordingTapsSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.recordingTaps(context)) }
    SwitchRow(PhosphorIcons.Regular.Vibrate, R.string.settings_taps_title, null, on) { on = it; AppSettings.setRecordingTaps(context, it) }
}

/** «Заметка готова» in the shade, off unless asked for: the last note to open with a tap, so a quick one is not forgotten. */
@Composable
internal fun SavedNoticeSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.savedNotice(context)) }
    SwitchRow(PhosphorIcons.Regular.Bell, R.string.saved_notice_setting, null, on) { on = it; AppSettings.setSavedNotice(context, it) }
}

/** «Убирать пустые записи»: turning it on clears the empty ones already there and says how many went to the trash. */
@Composable
internal fun EmptyNotesSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(AppSettings.dropEmpty(context)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SwitchRow(PhosphorIcons.Regular.Broom, R.string.empty_setting, R.string.empty_setting_text, on) { want ->
        on = want
        AppSettings.setDropEmpty(context, want)
        if (want) {
            scope.launch {
                val moved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.govorun.echo.notes.EmptyNotes.sweep(context) }
                if (moved > 0) android.widget.Toast.makeText(context, context.resources.getQuantityString(R.plurals.empty_swept, moved, moved), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
