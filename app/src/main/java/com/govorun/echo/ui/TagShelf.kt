package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsMerge
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.CircleDashed
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.PushPinSlash
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens

/*
 * One sheet for every tag, one line for every tag in it. Opened from the row above the list (or by holding one of its pills) it is
 * the list of tags: a tap shows a tag's notes, holding one starts choosing, as holding a note does, and whatever is chosen gets one
 * bar: colour, pin, a new name or one name for several, the notes to the archive, delete. The broom opens «Уборка»: tags that are
 * one word in two forms, the empty ones, those on one note and those not put on a note for half a year. Opened from a note it is the
 * same list, where a tap puts a tag on the note or takes it off. Nothing moves under the finger while the sheet is open.
 */


private sealed interface ShelfItem {
    val key: String
}

private data class ShelfHead(
    val title: String,
) : ShelfItem {
    override val key get() = "h-$title"
}

private data class ShelfTag(
    val tag: String,
    val part: Char,
) : ShelfItem {
    override val key get() = "$part-$tag"
}

/** Tags of one word in other forms, offered to become one. */
private data class ShelfSimilar(
    val tags: List<String>,
) : ShelfItem {
    override val key get() = "s-" + tags.joinToString("|")
}

/** Every empty tag on one line, to go at once. */
private data class ShelfEmpty(
    val tags: List<String>,
) : ShelfItem {
    override val key get() = "empty"
}

private data class ShelfCreate(
    val name: String,
) : ShelfItem {
    override val key get() = "create"
}

private data object ShelfNone : ShelfItem {
    override val key get() = "none"
}

private data object ShelfClean : ShelfItem {
    override val key get() = "clean"
}

/** What the sheet asks before it changes the notes. */
private sealed interface ShelfAsk

private data class AskRename(
    val tag: String,
) : ShelfAsk

private data class AskMerge(
    val tags: List<String>,
) : ShelfAsk

private data class AskArchive(
    val tags: List<String>,
    val notes: Int,
) : ShelfAsk

private data class AskDelete(
    val tags: List<String>,
) : ShelfAsk

/**
 * The list of every tag. [use] counts each tag's notes, archived ones too, and shows by each tag, nothing for none; [chosen] starts
 * the sheet with these tags chosen, as holding a pill of the row does. A tag made here is not pinned. [archiveCount] says how many
 * notes of this tab the chosen tags would send to the archive ([fromArchive]: bring back), so the sheet asks with the number;
 * [taggedCount] how many notes anywhere would lose them, asked before a delete.
 */
@Composable
internal fun TagShelf(
    tags: List<String>,
    pinned: List<String>,
    use: Map<String, Int>,
    lastUsed: Map<String, Long>,
    styles: Map<String, TagStyle>,
    filter: NotesFilter,
    onFilter: (NotesFilter) -> Unit,
    onCreate: (String, Int) -> Unit,
    onStyle: (String, TagStyle) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onRename: (String, String, String?) -> Unit,
    onDelete: (String) -> Unit,
    fromArchive: Boolean,
    archiveCount: (List<String>) -> Int,
    taggedCount: (List<String>) -> Int,
    onArchive: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    chosen: List<String> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    var cleaning by remember { mutableStateOf(false) }
    var painting by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ShelfAsk?>(null) }
    val picked = remember { mutableStateListOf<String>().apply { addAll(chosen) } }
    val made = remember { mutableStateListOf<String>() }
    val all = (made + tags).distinct()
    // A tag that is gone (deleted, or made one with another) leaves the choice.
    LaunchedEffect(tags) { picked.retainAll { it in all } }
    val selecting = picked.isNotEmpty()
    val done = {
        picked.clear()
        painting = false
    }
    // The sheet steps aside, so the list shows what left and the bar below it offers to bring it back.
    val archive = { chosenTags: List<String> ->
        onArchive(chosenTags)
        onDismiss()
    }
    val toggle = { tag: String ->
        if (!picked.remove(tag)) picked.add(tag)
        painting = false
    }
    val none = filter.withoutTags()
    val show = { next: NotesFilter ->
        onFilter(next)
        onDismiss()
    }
    val create = { name: String ->
        onCreate(name, tagColorIndex(name, null))
        if (name !in made) made.add(0, name)
        query = ""
    }
    val now = remember { System.currentTimeMillis() }
    val clean = remember(all, use, lastUsed) { cleanup(all, use, lastUsed, now) }
    val pins = pinned.filter { it in all }
    val rest = remember(all, pinned) { byName(all.filter { it !in pinned }) }
    val typed = cleanTag(query)
    val pinnedTitle = stringResource(R.string.tags_pinned)
    val restTitle = stringResource(if (pins.isEmpty()) R.string.tags_title else R.string.tags_others)
    val similarTitle = stringResource(R.string.tags_similar)
    val emptyTitle = stringResource(R.string.tags_empty_part, clean.empty.size)
    val rareTitle = stringResource(R.string.tags_rare_part, clean.rare.size)
    val staleTitle = stringResource(R.string.tags_stale_part, clean.stale.size)
    val items =
        when {
            cleaning ->
                buildList {
                    if (clean.similar.isNotEmpty()) {
                        add(ShelfHead(similarTitle))
                        clean.similar.forEach { add(ShelfSimilar(it)) }
                    }
                    if (clean.empty.isNotEmpty()) {
                        add(ShelfHead(emptyTitle))
                        add(ShelfEmpty(clean.empty))
                    }
                    if (clean.rare.isNotEmpty()) {
                        add(ShelfHead(rareTitle))
                        clean.rare.forEach { add(ShelfTag(it, 'o')) }
                    }
                    if (clean.stale.isNotEmpty()) {
                        add(ShelfHead(staleTitle))
                        clean.stale.forEach { add(ShelfTag(it, 'l')) }
                    }
                    if (clean.isEmpty) add(ShelfClean)
                }
            typed != null ->
                buildList {
                    searchTags(pins + rest, typed) { styles[it]?.shortName.orEmpty() }.forEach { add(ShelfTag(it, 'f')) }
                    if (sameTag(all, typed) == null && all.none { searchForm(styles[it]?.shortName.orEmpty()) == searchForm(typed) }) add(ShelfCreate(typed))
                }
            else ->
                buildList {
                    if (pins.isNotEmpty()) {
                        add(ShelfHead(pinnedTitle))
                        pins.forEach { add(ShelfTag(it, 'p')) }
                    }
                    if (rest.isNotEmpty()) {
                        add(ShelfHead(restTitle))
                        rest.forEach { add(ShelfTag(it, 'r')) }
                    }
                    if (all.isEmpty()) add(ShelfNone)
                }
        }
    ShelfFrame(
        onDismiss = onDismiss,
        // Back first lets go of the chosen tags, then leaves «Уборка», and only then closes the sheet.
        onBack =
            when {
                selecting -> done
                cleaning -> ({ cleaning = false })
                else -> null
            },
        header = {
            when {
                selecting -> {
                    IconButton(onClick = done) { Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.action_close_selection), modifier = Modifier.size(ProductTokens.Size.ActiveIcon)) }
                    Text(picked.size.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                cleaning -> {
                    IconButton(onClick = { cleaning = false }) { Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.action_back), modifier = Modifier.size(ProductTokens.Size.ActiveIcon)) }
                    Text(stringResource(R.string.tags_cleanup), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                else -> {
                    Text(stringResource(R.string.tags_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (!clean.isEmpty) {
                        IconButton(onClick = {
                            cleaning = true
                            query = ""
                        }) { Icon(PhosphorIcons.Regular.Broom, contentDescription = stringResource(R.string.tags_cleanup), modifier = Modifier.size(ProductTokens.Size.ActiveIcon)) }
                    }
                }
            }
        },
        query = query.takeIf { !cleaning },
        onQuery = { query = it },
        onEnter = { keyboard ->
            when (val step = tagEnter(all, pins + rest, query) { styles[it]?.shortName.orEmpty() }) {
                TagEnter.Hide -> keyboard()
                is TagEnter.Pick -> if (selecting) toggle(step.tag) else show(none.copy(tag = step.tag))
                is TagEnter.Create -> create(step.name)
            }
        },
        bottom = {
            if (selecting) {
                SelectionBar(picked.toList(), pinned, styles, painting, fromArchive, archiveCount(picked.toList()) > 0, { painting = true }, { index ->
                    picked.forEach { onStyle(it, TagStyle(index, it in pinned)) }
                    done()
                }, { on ->
                    picked.filter { (it in pinned) != on }.forEach { onPin(it, on) }
                    done()
                }, { ask = AskRename(picked.single()) }, { ask = AskMerge(picked.toList()) }, {
                    // Sending notes away is asked with their number; bringing them back is not.
                    if (fromArchive) archive(picked.toList()) else ask = AskArchive(picked.toList(), archiveCount(picked.toList()))
                }) { ask = AskDelete(picked.toList()) }
            }
        },
    ) {
        Box(Modifier.weight(1f, fill = false)) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ShelfHead -> TagSection(item.title)
                        is ShelfTag -> {
                            val tag = item.tag
                            ShelfLine(
                                tag,
                                tagColor(tag, styles[tag]),
                                typed.orEmpty(),
                                lit = !selecting && tag == filter.tag,
                                checked = if (selecting) tag in picked else null,
                                onClick = { if (selecting) toggle(tag) else show(none.copy(tag = tag)) },
                                onLongClick = { toggle(tag) },
                            ) { CountMark(use[tag] ?: 0) }
                        }
                        is ShelfSimilar -> SimilarLine(item.tags, styles) { ask = AskMerge(item.tags) }
                        is ShelfEmpty -> EmptyLine(item.tags) { ask = AskDelete(item.tags) }
                        is ShelfCreate -> CreateTagLine(item.name, TAG_COLORS[tagColorIndex(item.name, null)]) { create(item.name) }
                        ShelfNone -> NoTags()
                        ShelfClean -> QuietText(stringResource(R.string.tags_all_clean))
                    }
                }
            }
        }
    }
    when (val asked = ask) {
        is AskRename -> RenameDialog(asked.tag, styles[asked.tag]?.shortName.orEmpty(), { ask = null }) { name, short ->
            onRename(asked.tag, name, if (name != asked.tag && short == styles[asked.tag]?.shortName.orEmpty()) null else short)
            ask = null
            done()
        }
        is AskMerge -> MergeDialog(asked.tags, use, styles, { ask = null }) { into ->
            asked.tags.filter { it != into }.forEach { onRename(it, into, null) }
            ask = null
            done()
        }
        is AskArchive -> ArchiveDialog(asked.tags, asked.notes, { ask = null }) {
            ask = null
            archive(asked.tags)
        }
        is AskDelete -> DeleteDialog(asked.tags, taggedCount(asked.tags), { ask = null }) {
            asked.tags.forEach(onDelete)
            ask = null
            done()
        }
        null -> Unit
    }
}

/**
 * Tags of one note or of several chosen ones, in the same sheet: the notes' own tags first and ticked, then the pinned ones, then
 * the rest, the latest used first. A tap puts a tag on every chosen note or takes it off all of them, and the sheet stays open, so
 * several go on in a row; a name no tag has gets «Создать».
 */
@Composable
internal fun NoteTagsSheet(
    notes: List<NoteStore.Entry>,
    actions: NotesActions,
    onSet: (tag: String, add: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val made = remember { mutableStateListOf<String>() }
    // The order is fixed as the sheet opens, so nothing moves under the finger while tags go on and off.
    val opened = remember { actions.allTags }
    val own = remember { actions.allTags.filter { tagShare(notes, it) != TagShare.None } }
    val pins = remember { actions.pinnedTags.filter { it !in own } }
    // A tap shows at once; the notes are read from disk again a moment later.
    val pending = remember { mutableStateMapOf<String, TagShare>() }
    LaunchedEffect(notes) { pending.keys.filter { tagShare(notes, it) == pending[it] }.forEach { pending.remove(it) } }
    val shareOf = { tag: String -> pending[tag] ?: tagShare(notes, tag) }
    val set = { tag: String, add: Boolean ->
        pending[tag] = if (add) TagShare.All else TagShare.None
        onSet(tag, add)
    }
    val create = { name: String ->
        actions.onNewTag(name, tagColorIndex(name, null))
        if (name !in made) made.add(0, name)
        set(name, true)
        query = ""
    }
    val first = (made + own).distinct()
    val shown = (first + pins).toSet()
    val rest = (opened + actions.allTags).distinct().filter { it !in shown }
    val all = (first + pins + rest)
    val typed = cleanTag(query)
    val ownTitle = stringResource(if (notes.size > 1) R.string.tags_of_notes else R.string.tags_of_note)
    val pinnedTitle = stringResource(R.string.tags_pinned)
    val restTitle = stringResource(if (shown.isEmpty()) R.string.tags_title else R.string.tags_others)
    val items =
        if (typed != null) {
            searchTags(all, typed) { actions.tagStyles[it]?.shortName.orEmpty() }.map { ShelfTag(it, 'f') } + listOfNotNull(ShelfCreate(typed).takeIf { sameTag(all, typed) == null && all.none { searchForm(actions.tagStyles[it]?.shortName.orEmpty()) == searchForm(typed) } })
        } else {
            buildList {
                if (first.isNotEmpty()) add(ShelfHead(ownTitle))
                first.forEach { add(ShelfTag(it, 'o')) }
                if (pins.isNotEmpty()) add(ShelfHead(pinnedTitle))
                pins.forEach { add(ShelfTag(it, 'p')) }
                if (rest.isNotEmpty()) add(ShelfHead(restTitle))
                rest.forEach { add(ShelfTag(it, 'r')) }
                if (all.isEmpty()) add(ShelfNone)
            }
        }
    val title =
        if (notes.size > 1) {
            stringResource(R.string.tags_title_notes, pluralStringResource(R.plurals.notes_chosen, notes.size, notes.size))
        } else {
            stringResource(R.string.tags_title)
        }
    ShelfFrame(
        onDismiss = onDismiss,
        header = {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
        query = query,
        onQuery = { query = it },
        onEnter = { keyboard ->
            when (val step = tagEnter(all, all, query) { actions.tagStyles[it]?.shortName.orEmpty() }) {
                TagEnter.Hide -> keyboard()
                is TagEnter.Pick -> {
                    set(step.tag, shareOf(step.tag) != TagShare.All)
                    query = ""
                }
                is TagEnter.Create -> create(step.name)
            }
        },
    ) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ShelfHead -> TagSection(item.title)
                    is ShelfTag -> {
                        val tag = item.tag
                        val share = shareOf(tag)
                        ShelfLine(tag, tagColor(tag, actions.tagStyles[tag]), typed.orEmpty(), lit = false, checked = share == TagShare.All, role = Role.Checkbox, onClick = {
                            set(tag, share != TagShare.All)
                            if (typed != null) query = ""
                        }) {
                            when (share) {
                                TagShare.Some -> Text(stringResource(R.string.tags_some_of, notes.count { tag in it.tags }, notes.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TagShare.All, TagShare.None -> Unit
                            }
                        }
                    }
                    is ShelfCreate -> CreateTagLine(item.name, TAG_COLORS[tagColorIndex(item.name, null)]) { create(item.name) }
                    ShelfNone -> NoTags()
                    else -> Unit
                }
            }
        }
    }
}
