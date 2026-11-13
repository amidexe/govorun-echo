package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.List
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.Star
import com.govorun.echo.R
import com.product.ui.ProductTokens
import com.product.ui.ProductNavigationButton

/** The header, tag row and selection mode share one page colour. Only the search pill is a raised-looking surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesTopBar(
    selecting: Boolean,
    ids: List<String>,
    canMerge: Boolean,
    filterActive: Boolean,
    searching: Boolean,
    archiveScope: Boolean,
    actions: NotesActions,
    onTags: () -> Unit,
    query: String = "",
    found: Int = 0,
    visibleIds: List<String>,
    favoriteScope: Boolean = false,
    allSelectedFavorites: Boolean = false,
    onMenu: () -> Unit,
) {
    val computerOpen by com.govorun.echo.web.ComputerSession.connected.collectAsState()
    if (searching && !selecting) return SearchTopBar(query, found, actions)
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                if (selecting || archiveScope || favoriteScope) {
                    ProductNavigationButton(
                        PhosphorIcons.Regular.ArrowLeft,
                        stringResource(if (selecting) R.string.action_close_selection else R.string.action_back),
                        if (selecting) actions.onClearSelection else ({ actions.onTab(NotesTab.All) }),
                    )
                } else {
                    IconButton(onClick = onMenu) {
                        Box(Modifier.size(ProductTokens.Size.TopBarNavigationIcon)) {
                            Icon(
                                PhosphorIcons.Regular.List,
                                stringResource(if (computerOpen) R.string.computer_menu_active else R.string.action_menu),
                                Modifier.size(ProductTokens.Size.TopBarNavigationIcon),
                            )
                            if (computerOpen) Canvas(Modifier.size(6.dp).align(Alignment.TopEnd)) {
                                drawCircle(ProductTokens.Color.Success)
                            }
                        }
                    }
                }
            },
            title = {
                when {
                    selecting -> {
                        val description = stringResource(R.string.selected_count, ids.size)
                        Text(ids.size.toString(), modifier = Modifier.semantics { contentDescription = description })
                    }
                    favoriteScope -> Text(stringResource(R.string.favorites_title))
                    archiveScope -> Text(stringResource(R.string.screen_archive))
                    else -> SearchLauncher(actions.onToggleSearch)
                }
            },
            actions = {
                if (selecting) {
                    var more by remember { mutableStateOf(false) }
                    val enabled = ids.isNotEmpty()
                    TopIcon(if (archiveScope) PhosphorIcons.Regular.ArrowUUpLeft else PhosphorIcons.Regular.Archive, if (archiveScope) R.string.action_unarchive else R.string.action_archive, enabled = enabled) { actions.onArchive(ids, !archiveScope) }
                    TopIcon(if (allSelectedFavorites) PhosphorIcons.Fill.Star else PhosphorIcons.Regular.Star, if (allSelectedFavorites) R.string.action_unpin else R.string.action_pin, enabled = enabled) { actions.onSetFavorites(ids, !allSelectedFavorites) }
                    TopIcon(PhosphorIcons.Regular.Tag, R.string.action_tags, enabled = enabled, onClick = onTags)
                    TopIcon(PhosphorIcons.Regular.DotsThree, R.string.action_more, enabled = enabled) { more = true }
                    if (more) SelectionMenu(ids, canMerge, actions) { more = false }
                } else {
                    TopIcon(PhosphorIcons.Regular.SlidersHorizontal, R.string.filter_title, highlighted = filterActive, onClick = actions.onOpenFilters)
                    if (archiveScope || favoriteScope) TopIcon(PhosphorIcons.Regular.MagnifyingGlass, R.string.action_search, onClick = actions.onToggleSearch)
                }
            },
        )
        if (selecting) {
            val all = visibleIds.isNotEmpty() && visibleIds.all { it in ids }
            val state = if (all) ToggleableState.On else if (visibleIds.any { it in ids }) ToggleableState.Indeterminate else ToggleableState.Off
            Row(
                Modifier.fillMaxWidth().triStateToggleable(state, enabled = visibleIds.isNotEmpty(), role = Role.Checkbox) {
                    visibleIds.filter { (it in ids) == all }.forEach(actions.onCardClick)
                }.height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TriStateCheckbox(state = state, onClick = null)
                Text(stringResource(R.string.notes_select_all), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SearchLauncher(onClick: () -> Unit) {
    val description = stringResource(R.string.action_search)
    Surface(
        onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = description },
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.notes_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, description: Int, highlighted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, stringResource(description), tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else if (highlighted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(ProductTokens.Size.TopBarActionIcon))
    }
}
