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
import com.adamglin.phosphoricons.regular.Microphone
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.product.ui.ProductChoiceSheet
import com.product.ui.SettingsLinkRow

@Composable
internal fun MicrophoneSetting() {
    val context = LocalContext.current
    var value by remember { mutableStateOf(AppSettings.microphone(context)) }
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        AppSettings.MICROPHONE_PHONE to stringResource(R.string.microphone_phone),
        AppSettings.MICROPHONE_HEADSET to stringResource(R.string.microphone_headset),
    )
    fun label(choice: String) = labels.getValue(choice)
    SettingsLinkRow(
        PhosphorIcons.Regular.Microphone,
        stringResource(R.string.microphone_title),
        label(value),
        onClick = { open = true },
    )
    if (open) ProductChoiceSheet(
        title = stringResource(R.string.microphone_title),
        selected = value,
        options = listOf(AppSettings.MICROPHONE_PHONE, AppSettings.MICROPHONE_HEADSET),
        optionLabel = ::label,
        onSelected = { value = it; AppSettings.setMicrophone(context, it) },
        onDismissRequest = { open = false },
    )
}
