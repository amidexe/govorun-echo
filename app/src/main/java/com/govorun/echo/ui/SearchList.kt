package com.govorun.echo.ui

import com.adamglin.phosphoricons.regular.SlidersHorizontal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.SortDescending
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.notes.NoteIndex
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductChoiceSheet
import com.product.ui.ProductTokens
import com.product.ui.SettingsLinkRow

/**
 * A search in the order of how well each note answers, not by its date ([AppSettings.searchOrder]): the notes the words found, by
 * their BM25 ([NoteIndex.Found.score]; a note the index does not know yet, or found by its date or status only, after them, newest
 * first); a note only a near word found ([NoteIndex.Found.nearOnly]) after every note holding a word of the request itself.
 */
internal fun rankedNotes(
    visible: List<NoteStore.Entry>,
    found: NoteIndex.Found?,
): List<NoteStore.Entry> = if (found == null) visible else visible.sortedWith(compareBy<NoteStore.Entry> { it.id in found.nearOnly }.thenByDescending { found.score(it.id) })

/** The best answers under a heading of their own, each card with its day, as the search by relevance shows them. */
internal fun LazyListScope.rankedSection(
    notes: List<NoteStore.Entry>,
    title: Int,
    row: @Composable androidx.compose.foundation.lazy.LazyItemScope.(NoteStore.Entry, Boolean) -> Unit,
) {
    if (notes.isEmpty()) return
    if (title != R.string.search_ranked) item(key = "ranked-header") { SearchHeader(stringResource(title)) }
    items(notes, key = { "ranked-" + it.id }) { note -> row(note, note === notes.last()) }
    item(key = "ranked-gap") { Box(Modifier.height(10.dp)) }
}

/** How many best answers stand on top of the days in the layout "best". */
internal const val BEST_SHOWN = 3

@Composable
private fun SearchHeader(
    title: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = ProductTokens.Radius.Card, topEnd = ProductTokens.Radius.Card))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Lg),
    ) {
        Icon(PhosphorIcons.Regular.SortDescending, contentDescription = null, modifier = Modifier.size(ProductTokens.Size.SlotIcon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Порядок в поиске» in Settings: the best answer first, the best three and then the days, or the days as before. */
@Composable
internal fun SearchOrderSetting() {
    val context = LocalContext.current
    var value by remember { mutableStateOf(AppSettings.searchOrder(context)) }
    var open by remember { mutableStateOf(false) }
    fun label(option: String) =
        context.getString(
            when (option) {
                AppSettings.SEARCH_BEST -> R.string.search_order_best
                AppSettings.SEARCH_DAYS -> R.string.search_order_days
                else -> R.string.search_order_ranked
            },
        )
    SettingsLinkRow(PhosphorIcons.Regular.SortDescending, stringResource(R.string.search_order_title), value = label(value), onClick = { open = true })
    if (open) {
        ProductChoiceSheet(
            title = stringResource(R.string.search_order_title),
            selected = value,
            options = listOf(AppSettings.SEARCH_RANKED, AppSettings.SEARCH_BEST, AppSettings.SEARCH_DAYS),
            optionLabel = ::label,
            onSelected = { value = it; AppSettings.setSearchOrder(context, it) },
            onDismissRequest = { open = false },
        )
    }
}

/**
 * The list's search as Material sets it in an app bar: the field takes the title's place, an arrow closes it, the clear cross and
 * a quiet count of what was found stand in the actions. Nothing below moves when it opens.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    query: String,
    found: Int,
    actions: NotesActions,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (actions.takeSearchFocusRequest()) focus.requestFocus() }
    androidx.compose.material3.TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            com.product.ui.ProductNavigationButton(
                PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.action_close_search), actions.onToggleSearch,
            )
        },
        title = {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query, onValueChange = actions.onQueryChange,
                        modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { field ->
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(stringResource(R.string.notes_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                field()
                            }
                        },
                    )
                    if (query.isNotBlank()) {
                        Text(found.toString(), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { actions.onQueryChange("") }) {
                            Icon(PhosphorIcons.Regular.X, stringResource(R.string.tags_clear), Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = actions.onOpenFilters) { Icon(PhosphorIcons.Regular.SlidersHorizontal, stringResource(R.string.filter_title), Modifier.size(ProductTokens.Size.TopBarActionIcon)) }
        },
    )
}
