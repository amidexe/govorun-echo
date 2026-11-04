package com.govorun.echo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role

/** Independent conditions, applied together; dismissing the sheet leaves the list unchanged. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(filter: NotesFilter, onChange: (NotesFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(filter) }
    var calendar by remember { mutableStateOf(false) }
    if (calendar) {
        PeriodCalendarSheet(draft, onDismiss = { calendar = false }) { from, through ->
            draft = draft.copy(period = PeriodFilter.Custom, from = from, through = through)
            calendar = false
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.filter_group_period), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(PeriodFilter.All to R.string.filter_period_all, PeriodFilter.Today to R.string.filter_period_today, PeriodFilter.LastSeven to R.string.filter_period_seven).forEach { (value, label) ->
                    FilterPill(stringResource(label), draft.period == value) { draft = draft.copy(period = value, from = null, through = null) }
                }
                FilterPill(if (draft.period == PeriodFilter.Custom) periodLabel(draft) else stringResource(R.string.filter_dates), draft.period == PeriodFilter.Custom) { calendar = true }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Text(stringResource(R.string.filter_tag_color), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val colorNames = androidx.compose.ui.res.stringArrayResource(R.array.tag_color_names)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterPill(stringResource(R.string.filter_any_color), draft.tagColor == null) { draft = draft.copy(tagColor = null) }
                TAG_COLORS.forEachIndexed { index, color ->
                    FilterPill(colorNames[index], draft.tagColor == index, dot = color) { draft = draft.copy(tagColor = index, untagged = false, tag = null) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Text(stringResource(R.string.filter_audio), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(AudioFilter.All to R.string.filter_audio_all, AudioFilter.WithAudio to R.string.filter_audio_with, AudioFilter.WithoutAudio to R.string.filter_audio_without).forEach { (value, label) ->
                    FilterPill(stringResource(label), draft.audio == value) { draft = draft.copy(audio = value) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterPill(stringResource(R.string.tags_untagged), draft.untagged, multiple = true) { draft = draft.copy(untagged = !draft.untagged, tag = null, tagColor = null) }
                FilterPill(stringResource(R.string.filter_unread), draft.unreadOnly, multiple = true) { draft = draft.copy(unreadOnly = !draft.unreadOnly) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Text(stringResource(R.string.filter_group_sort), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(SortBy.Created to R.string.filter_sort_created, SortBy.Longest to R.string.filter_sort_longest).forEach { (value, label) ->
                    FilterPill(stringResource(label), draft.sort == value) { draft = draft.copy(sort = value) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { draft = draft.clearConditions() }) { Text(stringResource(R.string.filter_reset)) }
            Button(onClick = { onChange(draft.copy(status = StatusFilter.All)); onDismiss() }) { Text(stringResource(R.string.filter_apply)) }
        }
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, multiple: Boolean = false, dot: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Box(Modifier.semantics(mergeDescendants = true) { selected = on; role = if (multiple) Role.Checkbox else Role.RadioButton }) {
        TagPill(label, on, dot = dot, trailing = if (on) PhosphorIcons.Regular.Check else null, onClick = onClick)
    }
}
