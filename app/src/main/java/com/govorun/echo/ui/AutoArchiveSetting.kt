package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.govorun.echo.AppSettings
import com.govorun.echo.R

@Composable
internal fun AutoArchiveSetting(onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.autoArchive(context)) }
    SwitchRow(PhosphorIcons.Regular.Archive, R.string.auto_archive_title, R.string.auto_archive_summary, on) {
        on = it
        onChanged(it)
    }
}
