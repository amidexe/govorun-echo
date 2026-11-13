package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Subtitles
import com.adamglin.phosphoricons.regular.Video
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.product.ui.ProductRowGroup
import com.product.ui.ProductSheet
import com.product.ui.SettingsSwitchRow
import com.govorun.echo.TextOptions

/** What can go out: the words, the sound, the note as a video with its text, its subtitles for an editor. */
internal enum class ShareKind { Text, Audio, Video, Subtitles }

/** What waits for a choice: the notes to take out and what each kind they offer weighs (a video's weight is an estimate). */
internal class ExportRequest(
    val notes: List<NoteStore.Entry>,
    val sizes: Map<ShareKind, Long>,
    val severalPieces: Boolean,
    /** Each note's subtitles (SRT) by its id, made while the sizes were counted. */
    val subtitles: Map<String, String> = emptyMap(),
) {
    val audioBytes: Long get() = sizes[ShareKind.Audio] ?: 0
}

/**
 * The one sheet for taking notes out: tick what goes (text, sound, video, subtitles, each with its weight) and send it in one go;
 * the choice is kept for next time. Several pieces of text can carry their times and the mark on what was written to later.
 */
@Composable
internal fun ExportSheet(
    asked: ExportRequest,
    initial: TextOptions,
    initialKinds: Set<ShareKind>,
    onSend: (Set<ShareKind>, TextOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var options by remember { mutableStateOf(initial) }
    var kinds by remember { mutableStateOf(initialKinds.filter { it in asked.sizes }.toSet().ifEmpty { setOf(asked.sizes.keys.first()) }) }
    ProductSheet(stringResource(R.string.export_title), onDismiss) {
        ProductRowGroup {
            asked.sizes.forEach { (kind, bytes) ->
                KindRow(kind, bytes, kind in kinds) { on -> kinds = if (on) kinds + kind else kinds - kind }
            }
        }
        if (asked.severalPieces && ShareKind.Text in kinds) {
            ProductRowGroup {
                OptionSwitch(R.string.export_stamps, R.string.export_stamps_hint, options.stamps) { options = options.copy(stamps = it) }
                OptionSwitch(R.string.export_appended, R.string.export_appended_hint, options.markAppended, enabled = options.stamps) { options = options.copy(markAppended = it) }
            }
        }
        // A large sound goes straight into a file the owner picks, so the phone never needs room for a second copy.
        val save = ShareKind.Audio in kinds && asked.audioBytes > SAVE_TO_FILE_BYTES
        androidx.compose.material3.Button(
            onClick = { onSend(kinds, options) },
            enabled = kinds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = com.product.ui.productButtonColors(),
        ) { Text(stringResource(if (save) R.string.export_save else R.string.export_send, formatSize(kinds.sumOf { asked.sizes[it] ?: 0 }))) }
    }
}

/** Above this much sound a file is saved where the owner says instead of going through the share sheet. */
internal const val SAVE_TO_FILE_BYTES = 300L * 1024 * 1024

@Composable
private fun KindRow(
    kind: ShareKind,
    bytes: Long,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val (icon, label) =
        when (kind) {
            ShareKind.Text -> PhosphorIcons.Regular.FileText to R.string.export_option_text
            ShareKind.Audio -> PhosphorIcons.Regular.SpeakerHigh to R.string.export_option_audio
            ShareKind.Video -> PhosphorIcons.Regular.Video to R.string.export_option_video
            ShareKind.Subtitles -> PhosphorIcons.Regular.Subtitles to R.string.export_option_subtitles
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ProductTokens.Size.SheetAction)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = ProductTokens.Spacing.Screen, vertical = ProductTokens.Spacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.RowContent),
    ) {
        com.product.ui.ChoiceMark(checked)
        Icon(icon, contentDescription = null, modifier = Modifier.size(ProductTokens.Size.GroupedRowIcon))
        Text(stringResource(label, formatSize(bytes)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OptionSwitch(
    title: Int,
    hint: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) = SettingsSwitchRow(
    title = stringResource(title),
    summary = stringResource(hint),
    checked = checked && enabled,
    onCheckedChange = onChange,
    enabled = enabled,
)

/** While a video is being made: how far it got, and a way to stop it. */
@Composable
internal fun VideoProgress(
    progress: Float,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.export_video_making)) },
        text = { androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
