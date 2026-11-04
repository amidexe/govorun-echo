package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer

@Composable
internal fun RaiseToListenSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.raiseToListen(context)) }
    SwitchRow(PhosphorIcons.Regular.SpeakerHigh, R.string.raise_to_listen_title, R.string.raise_to_listen_summary, on) {
        on = it
        AppSettings.setRaiseToListen(context, it)
        NotePlayer.get(context).refreshRaiseToListen()
    }
}
