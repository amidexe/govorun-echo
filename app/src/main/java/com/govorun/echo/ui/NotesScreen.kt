package com.govorun.echo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsMerge
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.ArrowsInLineVertical
import com.adamglin.phosphoricons.regular.ArrowsOutLineVertical
import com.adamglin.phosphoricons.regular.List
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Tray
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.recognition.RecognitionStatus
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.record.RecordingState
import com.product.ui.FeedCard
import com.product.ui.ProductTokens
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NotesScreen(
    notes: List<NoteStore.Entry>,
    tab: NotesTab,
    filter: NotesFilter,
    recordingState: RecordingState,
    setup: SetupState,
    selected: Set<String>,
    selectMode: Boolean,
    query: String?,
    pausedReason: String?,
    playing: NotePlayer.State,
    snackbar: SnackbarHostState,
    actions: NotesActions,
    drawer: androidx.compose.material3.DrawerState,
    onMenu: () -> Unit,
) {
    val archiveScope = tab == NotesTab.Archive
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val searching = query != null
    // A search asks the index off the main thread; until its answer to this very request is in, the list shown stays.
    val answer by androidx.compose.runtime.produceState<Pair<String, com.govorun.echo.notes.NoteIndex.Found?>?>(null, query, notes) {
        value = if (query.isNullOrBlank()) null else query to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.govorun.echo.notes.NoteIndex.get(context).find(query, notes) }.getOrNull() }
    }
    val ready = query.isNullOrBlank() || answer?.first == query
    val found = answer?.second?.takeIf { ready }
    // How the words are looked for, for the cards' passages: as the index looked for them, or whole until it answers.
    val search = remember(query, found, filter.period) { found?.words ?: (query ?: if (filter.period != PeriodFilter.All) "" else null)?.takeIf { it.isNotBlank() || filter.period != PeriodFilter.All }?.let { com.govorun.echo.notes.SearchWords(it) } }
    // Read again with every search opened: the layout is chosen in Settings.
    val order = remember(searching) { com.govorun.echo.AppSettings.searchOrder(context) }
    val byRelevance = filter.sort != SortBy.Longest && !query.isNullOrBlank() && order != com.govorun.echo.AppSettings.SEARCH_DAYS
    val anyWord = anyWordFor(query, order)
    val shown = remember { arrayOfNulls<List<NoteStore.Entry>>(1) }
    val visible =
        if (ready) {
            remember(notes, tab, filter, query, actions.tagStyles, found, anyWord, resources) {
                visibleNotes(notes, tab, filter, query, statusText = { resources.getString(statusLabel(it)) }, colorOf = { tagColorIndex(it, actions.tagStyles[it]) }, found = found, anyWord = anyWord, aliasOf = { actions.tagStyles[it]?.shortName.orEmpty() })
            }.also { shown[0] = it }
        } else {
            shown[0].orEmpty()
        }
    // By relevance: one list, or its best three on top of the days; otherwise the days alone.
    val ranked = if (byRelevance) remember(visible, found) { rankedNotes(visible, found) } else visible
    // «Сначала длинные» is one list, each row with its date; days would scatter it.
    val longest = filter.sort == SortBy.Longest && !byRelevance
    val best = if (longest) visible else if (!byRelevance) emptyList() else if (order == com.govorun.echo.AppSettings.SEARCH_RANKED) ranked else ranked.take(BEST_SHOWN)
    // Favorites stay in the same chronology; they never displace fresh notes at the top.
    val rest = if (longest) emptyList() else if (byRelevance) ranked.drop(best.size).sortedByDescending { it.placedAt(filter.sort) } else visible
    val groupSearchDays = !query.isNullOrBlank() && order != com.govorun.echo.AppSettings.SEARCH_RANKED
    val days = remember(rest, filter.sort, groupSearchDays) { if (groupSearchDays) rest.groupBy { dayOf(it.placedAt(filter.sort)) } else emptyMap() }
    val selecting = selected.isNotEmpty() || selectMode
    val chosen = notes.filter { it.id in selected }
    val canMerge = chosen.size >= 2 && chosen.all { it.state == NoteStore.STATE_DONE }
    val recording = recordingState is RecordingState.Recording || recordingState is RecordingState.Starting
    val recordingId = (recordingState as? RecordingState.Recording)?.noteId
    var tagging by remember { mutableStateOf(false) }
    // The tag sheet: null while closed, else the tags it opens with chosen (a held pill of the row).
    var shelf by remember { mutableStateOf<List<String>?>(null) }
    val made = if (archiveScope) emptySet() else actions.madeTags
    val countsShown = remember { arrayOfNulls<Map<String, Int>>(1) }
    val counts =
        if (ready) {
            remember(notes, tab, filter, query, made, found, anyWord, actions.tagStyles, resources) { tagCounts(notes, tab, filter, query, made, found, { resources.getString(statusLabel(it)) }, anyWord, { actions.tagStyles[it]?.shortName.orEmpty() }).toMap() }.also { countsShown[0] = it }
        } else {
            countsShown[0].orEmpty()
        }
    // The archive's row keeps only the pinned tags its notes carry.
    val rowTags = actions.pinnedTags.filter { !archiveScope || (counts[it] ?: 0) > 0 }.map { it to (counts[it] ?: 0) }
    val list = rememberLazyListState()
    val revealImport by com.govorun.echo.notes.NoteImport.pendingReveal.collectAsState()
    LaunchedEffect(revealImport, visible) {
        val id = revealImport ?: return@LaunchedEffect
        if (visible.any { it.id == id }) {
            list.scrollToItem(0)
            com.govorun.echo.notes.NoteImport.revealed(id)
        }
    }
    val header = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = rememberTopAppBarState(), canScroll = { !searching && !selecting && !drawer.isOpen },
    )
    LaunchedEffect(searching, selecting, drawer.isOpen) {
        if (searching || selecting || drawer.isOpen) header.state.heightOffset = 0f
    }
    val playingButtonShown by rememberRowButtonInView(list, playing.noteId)
    val recordingRowShown by rememberRowButtonInView(list, recordingId)

        Scaffold(
            modifier = Modifier.nestedScroll(header.nestedScrollConnection).statusBarVeil(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CollapsingNotesHeader(header.state) {
                val selectedNotes = notes.filter { it.id in selected }
                NotesTopBar(selecting, selected.toList(), canMerge, filter.active, searching, archiveScope, actions, onTags = { tagging = true }, query = query.orEmpty(), found = ranked.size, visibleIds = visible.map { it.id }, favoriteScope = tab == NotesTab.Favorites, allSelectedFavorites = selected.isNotEmpty() && selectedNotes.size == selected.size && selectedNotes.all { it.pinned }) { onMenu() }
                if (!selecting && !searching) TagChips(rowTags, filter, searching, actions.tagStyles, actions.onFilter, actions.onTagOrder, { shelf = listOf(it) }) { shelf = emptyList() }
                if (!selecting && filter.active) ActiveFilters(filter, actions.onFilter)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = DOCK_CLEARANCE),
                ) {
                    pausedReason?.takeIf { it == RecognitionStatus.LOW_BATTERY }?.let { item(key = "paused") { PausedCard(it) } }
                    if (com.govorun.echo.update.AppUpdates.enabled) item(key = "update") { UpdateCard() }
                    // Opened but still empty, a search changes nothing in the list: nothing jumps before a letter is typed.
                    if (query.isNullOrBlank() && tab == NotesTab.All) setupCard(setup, actions)
                    if (visible.isEmpty() && archiveScope && query.isNullOrBlank() && !filter.active) {
                        item(key = "archive-empty") { ArchiveEmpty() }
                    } else if (visible.isEmpty() && tab == NotesTab.Favorites && query.isNullOrBlank() && !filter.active) {
                        item(key = "favorites-empty") { FavoritesEmpty() }
                    } else if (visible.isEmpty() && !recording) {
                        item(key = "empty") { EmptyState(notes.none { !it.archived }, filter.active || !filter.noTag || searching) }
                    }
                    rankedSection(best, if (longest) R.string.filter_sort_longest else if (order == com.govorun.echo.AppSettings.SEARCH_RANKED) R.string.search_ranked else R.string.search_best) { note, last ->
                        NoteRow(note, last, note.id in selected, selecting, playing.takeIf { it.noteId == note.id }, actions, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), search, first = note === best.first())
                    }
                    if (!groupSearchDays) {
                        items(rest, key = { it.id }) { note ->
                            NoteRow(note, note === rest.last(), note.id in selected, selecting, playing.takeIf { it.noteId == note.id }, actions, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), search, first = note === rest.first())
                        }
                    } else days.forEach { (day, entries) ->
                        item(key = "day-$day") { NoteSectionHeading(dayLabel(context, day)) }
                        items(entries, key = { it.id }) { note ->
                            NoteRow(note, note === entries.last(), note.id in selected, selecting, playing.takeIf { it.noteId == note.id }, actions, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), search, first = note === entries.first())
                        }
                        item(key = "gap-$day") { Box(Modifier.height(10.dp)) }
                    }
                }
                }
                LaunchedEffect(TagsRequest.open) { if (TagsRequest.open) { shelf = emptyList(); TagsRequest.open = false } }
                shelf?.let { held ->
                    // When a note last got each tag, for «Уборка».
                    val lastUsed = remember(notes) { notes.flatMap { n -> n.tags.map { it to n.startedAtMs } }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() } }
                    TagShelf(
                        actions.allTags, actions.pinnedTags, actions.tagUse, lastUsed, actions.tagStyles, filter, actions.onFilter,
                        actions.onNewTag, actions.onTagStyle, actions.onPinTag, actions.onRenameTag, actions.onDeleteTag,
                        fromArchive = archiveScope,
                        // The notes of the chosen tags in this tab go to the archive, or back from it.
                        taggedCount = { tags -> notes.count { n -> n.tags.any { it in tags } } },
                        archiveCount = { tags -> notes.count { n -> n.archived == archiveScope && n.tags.any { it in tags } } },
                        onArchive = { tags -> actions.onArchive(notes.filter { n -> n.archived == archiveScope && n.tags.any { it in tags } }.map { it.id }, !archiveScope) },
                        onDismiss = { shelf = null },
                        chosen = held,
                    )
                }
                if (tagging) NoteTagsSheet(chosen, actions, { tag, add -> actions.onSetTag(selected.toList(), tag, add) }) { tagging = false }
                if (!selecting) {
                    NowPlayingBar(notes, playing, showsNowPlaying(playing, playingButtonShown) && !recording, actions.onCardClick, Modifier.align(Alignment.BottomStart))
                    // The note being recorded gets the same bar while its card is out of sight.
                    RecordingBar(notes, recordingState, !recordingRowShown, actions, Modifier.align(Alignment.BottomStart))
                    RecordButton(recording, actions, Modifier.align(Alignment.BottomEnd))
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = if (selecting || keyboardVisible) 16.dp else DOCK_CLEARANCE),
                ) { data ->
                    // The same quiet surface as the cards, not the white strip Material makes by default.
                    Snackbar(
                        data,
                        shape = RoundedCornerShape(ProductTokens.Radius.Card),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
}

private fun androidx.compose.foundation.lazy.LazyListScope.setupCard(
    setup: SetupState,
    actions: NotesActions,
) {
    if (!setup.microphone) {
        item(key = "setup-mic") { SetupCard(R.string.setup_mic_title, R.string.setup_mic_text, R.string.action_allow, actions.onRequestMicrophone) }
        return
    }
    when (setup.govorun) {
        GovorunState.NotInstalled -> item(key = "setup-govorun") { SetupCard(R.string.setup_govorun_missing_title, R.string.setup_govorun_missing_text, R.string.action_get_govorun, actions.onGetGovorun) }
        GovorunState.TooOld -> item(key = "setup-govorun") { SetupCard(R.string.setup_govorun_old_title, R.string.setup_govorun_old_text, R.string.action_update_govorun, actions.onGetGovorun) }
        GovorunState.NeedsAccess ->
            item(key = "setup-govorun") { SetupCard(R.string.setup_govorun_access_title, R.string.setup_govorun_access_text, R.string.action_allow, actions.onAuthorizeGovorun) }
        GovorunState.ModelUnavailable -> item(key = "setup-govorun") { SetupCard(R.string.setup_govorun_model_title, R.string.setup_govorun_model_text, null) {} }
        else -> Unit
    }
}

@Composable
private fun SetupCard(
    title: Int,
    text: Int,
    button: Int?,
    onClick: () -> Unit,
) {
    FeedCard(Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null) OutlinedButton(onClick = onClick) { Text(stringResource(button)) }
        }
    }
}

@Composable
private fun EmptyState(
    noNotes: Boolean,
    filtered: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Lg)) {
        Text(
            stringResource(if (noNotes) R.string.empty_title else if (filtered) R.string.empty_filtered else R.string.empty_tab),
            style = MaterialTheme.typography.titleMedium,
        )
        if (noNotes) Text(stringResource(if (com.govorun.echo.BuildConfig.HAS_HOTKEY) R.string.empty_text else R.string.empty_text_no_hotkey), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PausedCard(reason: String) {
    val hot = reason == RecognitionStatus.HOT
    SetupCard(
        if (hot) R.string.paused_hot_title else R.string.paused_battery_title,
        if (hot) R.string.paused_hot_text else R.string.paused_battery_text,
        null,
    ) {}
}

@Composable
private fun ArchiveEmpty() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Lg)) {
        Text(stringResource(R.string.archive_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.archive_empty_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FavoritesEmpty() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Lg)) {
        Text(stringResource(R.string.favorites_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.favorites_empty_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
