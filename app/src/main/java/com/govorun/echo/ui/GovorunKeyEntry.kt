package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.govorun.echo.R
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsInfoRow
import com.govorun.echo.smart.GovorunSmart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Кнопка громкости через Говорун» (an experiment): Govorun, which already has accessibility, lends Echo volume-down on the lock
 * screen. One «Разрешить» opens Govorun's own permission dialog; Echo asks for no accessibility of its own. The state is Govorun's,
 * read again each time the screen comes back.
 */
@Composable
internal fun GovorunKeyEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GovorunSmart.Status?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(round) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { status = withContext(Dispatchers.IO) { GovorunSmart(context).status() } }
    }
    val s = status ?: return
    if (!s.volumeKeyKnown) {
        SettingsInfoRow(PhosphorIcons.Regular.SpeakerHigh, stringResource(R.string.govorun_key_title), stringResource(R.string.govorun_key_update))
        return
    }
    // A permission owned by another app is a destination, not a local switch.
    SettingsLinkRow(
        PhosphorIcons.Regular.SpeakerHigh,
        stringResource(R.string.govorun_key_title),
        stringResource(if (s.volumeKeyGranted) R.string.govorun_key_on else R.string.govorun_key_offer),
    ) {
        // The permission is Govorun's: its own window asks (and is where it is taken back).
        scope.launch {
            if (!openGovorunAction(context, GovorunSmart.ACTION_VOLUME_KEY)) {
                android.widget.Toast.makeText(context, R.string.govorun_key_open_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
            round++
        }
    }
}
