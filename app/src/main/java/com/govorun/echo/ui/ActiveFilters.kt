package com.govorun.echo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.product.ui.ProductTokens
import java.time.format.DateTimeFormatter

@Composable
internal fun periodLabel(filter: NotesFilter): String {
    val locale = java.util.Locale.forLanguageTag("ru")
    return when (filter.period) {
    PeriodFilter.All -> stringResource(R.string.filter_period_all)
    PeriodFilter.Today -> stringResource(R.string.filter_period_today)
    PeriodFilter.LastSeven -> stringResource(R.string.filter_period_seven)
    PeriodFilter.Custom -> {
        val fmt = DateTimeFormatter.ofPattern("d MMM uuuu", locale)
        val from = filter.from?.format(fmt).orEmpty()
        if (filter.from == filter.through) from else "$from — ${filter.through?.format(fmt).orEmpty()}"
    }
}

}

/** Each visible condition has its own reset; removing a date keeps audio, tags and order. */
@Composable
internal fun ActiveFilters(filter: NotesFilter, onChange: (NotesFilter) -> Unit) {
    val parts = buildList<Pair<String, () -> Unit>> {
        if (filter.period != PeriodFilter.All) add(periodLabel(filter) to { onChange(filter.copy(period = PeriodFilter.All, from = null, through = null)) })
        if (filter.audio != AudioFilter.All) add(stringResource(if (filter.audio == AudioFilter.WithAudio) R.string.filter_audio_with else R.string.filter_audio_without) to { onChange(filter.copy(audio = AudioFilter.All)) })
        if (filter.untagged) add(stringResource(R.string.tags_untagged) to { onChange(filter.copy(untagged = false)) })
        if (filter.unreadOnly) add(stringResource(R.string.filter_unread) to { onChange(filter.copy(unreadOnly = false)) })
        if (filter.sort != SortBy.Created) add(stringResource(if (filter.sort == SortBy.Longest) R.string.filter_sort_longest else R.string.filter_sort_modified) to { onChange(filter.copy(sort = SortBy.Created)) })
    }
    if (parts.isEmpty() && filter.tagColor == null) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filter.tagColor?.takeIf { it in TAG_COLORS.indices }?.let { color ->
            TagPill(androidx.compose.ui.res.stringArrayResource(R.array.tag_color_names)[color], true, dot = TAG_COLORS[color], trailing = PhosphorIcons.Regular.X) { onChange(filter.copy(tagColor = null)) }
        }
        parts.forEach { (label, clear) ->
            TagPill(label, true, trailing = PhosphorIcons.Regular.X, onClick = clear)
        }
    }
}
