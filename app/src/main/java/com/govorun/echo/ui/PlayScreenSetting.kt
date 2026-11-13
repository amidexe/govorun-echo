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
import com.adamglin.phosphoricons.regular.Eye
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.product.ui.ProductChoiceSheet
import com.product.ui.SettingsLinkRow

/** What the screen does while a note plays: dim and let go after a while (default), stay on, or follow the system. */
@Composable
internal fun PlayScreenSetting() {
    val context = LocalContext.current
    var value by remember { mutableStateOf(AppSettings.playScreen(context)) }
    var open by remember { mutableStateOf(false) }
    fun label(option: String) =
        context.getString(
            when (option) {
                AppSettings.SCREEN_ALWAYS -> R.string.play_screen_always
                AppSettings.SCREEN_SYSTEM -> R.string.play_screen_system
                else -> R.string.play_screen_dim
            },
        )
    SettingsLinkRow(
        PhosphorIcons.Regular.Eye,
        stringResource(R.string.play_screen_title),
        value = label(value),
        onClick = { open = true },
    )
    if (open) {
        ProductChoiceSheet(
            title = stringResource(R.string.play_screen_title),
            selected = value,
            options = listOf(AppSettings.SCREEN_DIM, AppSettings.SCREEN_ALWAYS, AppSettings.SCREEN_SYSTEM),
            optionLabel = ::label,
            onSelected = { value = it; AppSettings.setPlayScreen(context, it) },
            onDismissRequest = { open = false },
        )
    }
}
