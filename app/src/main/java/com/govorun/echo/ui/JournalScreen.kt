package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.FolderSimple
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.TextAa
import com.govorun.echo.Journal
import com.govorun.echo.R
import com.product.ui.ProductDivider
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsSection
import com.product.ui.productScreenPadding
import java.text.DateFormat

/** What happened lately, newest first: stops, recognition problems, copy errors, command results. */
@Composable
internal fun JournalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(Journal.entries(context)) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    ProductScaffold(title = stringResource(R.string.journal_title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            item {
                SettingsSection(stringResource(R.string.journal_title)) {
                    if (entries.isEmpty()) SettingsInfoRow(PhosphorIcons.Regular.FileText, stringResource(R.string.journal_empty))
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) ProductDivider()
                        val icon =
                            when (entry.kind) {
                                Journal.RECORDING -> PhosphorIcons.Regular.Microphone
                                Journal.RECOGNITION -> PhosphorIcons.Regular.TextAa
                                Journal.BACKUP -> PhosphorIcons.Regular.FolderSimple
                                Journal.SMART -> PhosphorIcons.Regular.Sparkle
                                else -> PhosphorIcons.Regular.Lightning
                            }
                        SettingsInfoRow(icon, entry.label, format.format(entry.atMs))
                    }
                }
            }
            if (entries.isNotEmpty()) {
                item {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                        TextButton(onClick = {
                            val text = entries.joinToString("\n") { "${format.format(it.atMs)}  ${it.label}" }
                            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("journal", text))
                            android.widget.Toast.makeText(context, R.string.journal_copied, android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.journal_copy)) }
                        TextButton(onClick = {
                            Journal.clear(context)
                            entries = emptyList()
                        }) { Text(stringResource(R.string.journal_clear)) }
                    }
                }
            }
        }
    }
}
