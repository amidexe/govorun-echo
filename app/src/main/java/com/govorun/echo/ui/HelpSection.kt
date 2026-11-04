package com.govorun.echo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.govorun.echo.R
import com.product.ui.SettingsSection

/** A settings card with an «i» in its corner: where a choice is not obvious, the plain explanation is one tap away, not a paragraph in the list. */
@Composable
internal fun HelpSection(
    title: String,
    help: Int,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsSection(title, content)
        IconButton(onClick = { open = true }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp).size(40.dp)) {
            Icon(PhosphorIcons.Regular.Info, contentDescription = stringResource(R.string.help_open), modifier = Modifier.size(20.dp))
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(stringResource(help)) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.help_close)) } },
        )
    }
}
