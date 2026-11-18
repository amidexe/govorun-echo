package com.govorun.echo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Content-sized bottom bar with Material's bar insets, so labels can wrap at larger font sizes. */
@Composable
internal fun NoteActionBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(BottomAppBarDefaults.windowInsets)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            content()
        }
    }
}
