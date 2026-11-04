package com.govorun.echo.ui

import androidx.compose.material3.RadioButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ModalBottomSheetProperties
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsMerge
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.PushPinSlash
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import kotlinx.coroutines.launch


/*
 * The parts of the tag sheet ([TagShelf]): what «Уборка» finds, the bar of the chosen tags, the lines that are not one tag, the
 * questions asked before the notes change.
 */

/** Tags that are one word in different forms or spellings («рецепт», «Рецепты»), each group with the most used tag first. */
internal fun similarTags(
    tags: List<String>,
    use: Map<String, Int>,
): List<List<String>> = tags.groupBy(::tagKey).values.filter { it.size > 1 }.map { group -> group.sortedByDescending { use[it] ?: 0 } }

/** A tag reduced to the stems of its words, so «Рецепты» and «рецепт» or «Дом-дача» and «дом дача» meet. */
private fun tagKey(tag: String): String =
    searchForm(tag).split(NOT_WORD).filter { it.isNotEmpty() }.joinToString(" ") { com.govorun.echo.notes.WordForms.term(it) }

private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

/** What «Уборка» offers; a tag stands in the first part it fits, so it is never offered twice. */
internal data class Cleanup(
    val similar: List<List<String>>,
    val empty: List<String>,
    val rare: List<String>,
    val stale: List<String>,
) {
    val isEmpty get() = similar.isEmpty() && empty.isEmpty() && rare.isEmpty() && stale.isEmpty()
}

/** [use] counts the notes of every tag, archived ones too; [lastUsed] is when a note last got it. */
internal fun cleanup(
    tags: List<String>,
    use: Map<String, Int>,
    lastUsed: Map<String, Long>,
    now: Long,
): Cleanup {
    val similar = similarTags(tags, use)
    val seen = similar.flatten().toMutableSet()
    val empty = byName(tags.filter { it !in seen && (use[it] ?: 0) == 0 }).also { seen += it }
    val rare = byName(tags.filter { it !in seen && use[it] == 1 }).also { seen += it }
    val stale = tags.filter { it !in seen && (lastUsed[it] ?: now) < now - STALE_MS }.sortedBy { lastUsed[it] }
    return Cleanup(similar, empty, rare, stale)
}

/** Tags in the order of the alphabet, whatever the case and «ё». */
internal fun byName(tags: List<String>): List<String> = tags.sortedWith(compareBy<String> { searchForm(it) }.thenBy { it })

/** Half a year without a new note. */
private const val STALE_MS = 183L * 24 * 60 * 60 * 1000

/** The sheet both lists stand in: a header line, the search when [query] is not null, the list, and a bar under it when there is one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfFrame(
    onDismiss: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    onBack: (() -> Unit)? = null,
    query: String?,
    onQuery: (String) -> Unit,
    searchHint: String? = null,
    onEnter: (hideKeyboard: () -> Unit) -> Unit,
    bottom: @Composable () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // Back is the sheet's own: the window's properties are read once, while what Back does changes as tags are chosen.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BackHandler { onBack?.invoke() ?: scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
        Column(Modifier.fillMaxHeight().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, content = header)
            if (query != null) {
                Box(Modifier.padding(vertical = 4.dp)) {
                    TagSearchField(query, onQuery, onEnter = {
                        onEnter {
                            keyboard?.hide()
                            focus.clearFocus()
                        }
                    }, hint = searchHint)
                }
            }
            // The list keeps the tallest it has been, so the field stays in place as a search narrows it.
            Column(Modifier.weight(1f), content = body)
            bottom()
        }
    }
}

/** What can be done with the chosen tags, as icons: colour, pin, a new name (one tag) or one name for all (several), archive, delete. */
@Composable
internal fun SelectionBar(
    picked: List<String>,
    pinned: List<String>,
    styles: Map<String, TagStyle>,
    painting: Boolean,
    fromArchive: Boolean,
    canArchive: Boolean,
    onPaint: () -> Unit,
    onColour: (Int) -> Unit,
    onPin: (Boolean) -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    if (painting) {
        // One colour ticked only when every chosen tag has it.
        val colours = picked.map { tagColorIndex(it, styles[it]) }.distinct()
        Box(Modifier.padding(vertical = 10.dp)) { ColorRow(colours.singleOrNull() ?: -1, onColour) }
        return
    }
    val allPinned = picked.all { it in pinned }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        BarAction(PhosphorIcons.Regular.Palette, stringResource(R.string.tags_color), onClick = onPaint)
        BarAction(if (allPinned) PhosphorIcons.Regular.PushPinSlash else PhosphorIcons.Regular.PushPin, stringResource(if (allPinned) R.string.tags_unpin else R.string.tags_pin)) { onPin(!allPinned) }
        if (picked.size == 1) BarAction(PhosphorIcons.Regular.PencilSimple, stringResource(R.string.tags_rename), onClick = onRename)
        if (picked.size > 1) BarAction(PhosphorIcons.Regular.ArrowsMerge, stringResource(R.string.tags_merge), onClick = onMerge)
        // Only when the chosen tags have notes in this tab: else there is nothing to send away or bring back.
        if (canArchive) {
            if (fromArchive) BarAction(PhosphorIcons.Regular.ArrowUUpLeft, stringResource(R.string.tags_unarchive), onClick = onArchive)
            else BarAction(PhosphorIcons.Regular.Archive, stringResource(R.string.tags_archive), onClick = onArchive)
        }
        BarAction(PhosphorIcons.Regular.Trash, stringResource(R.string.tags_delete), MaterialTheme.colorScheme.error, onDelete)
    }
}

@Composable
internal fun BarAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) = IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(ProductTokens.Size.ActiveIcon)) }

/** How many notes carry a tag, quiet at the end of its line; nothing when none does («Уборка» lists those). */
@Composable
internal fun CountMark(count: Int) =
    Text(
        if (count == 0) "" else count.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
internal fun Modifier.lit(on: Boolean) = if (on) background(MaterialTheme.colorScheme.secondaryContainer) else this

/** Tags of one word in other forms: their dots and names, and the button that makes them one. */
@Composable
internal fun SimilarLine(
    tags: List<String>,
    styles: Map<String, TagStyle>,
    onMerge: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ProductTokens.Size.Row).clip(RoundedCornerShape(ProductTokens.Radius.Md)).clickable(onClick = onMerge).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TagDot(tagColor(tags.first(), styles[tags.first()]))
        Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onMerge) { Icon(PhosphorIcons.Regular.ArrowsMerge, contentDescription = stringResource(R.string.tags_merge), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
    }
}

/** The empty tags by name on one line, with the button that deletes them all. */
@Composable
internal fun EmptyLine(
    tags: List<String>,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = ProductTokens.Size.Row).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tags.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) { Icon(PhosphorIcons.Regular.Trash, contentDescription = stringResource(R.string.tags_delete_empty), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
internal fun QuietText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))

/** A new name for one tag; a name another tag has makes the two one. */
@Composable
internal fun RenameDialog(
    tag: String,
    shortName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(tag, TextRange(tag.length))) }
    val clean = cleanTag(name.text)
    var short by remember { mutableStateOf(TextFieldValue(shortName)) }
    val save = { clean?.let { onSave(it, short.text.trim()) } ?: Unit }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_rename)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TagField(name, { name = it }, stringResource(R.string.tags_full_name)) { save() }
            TagField(short, { short = it }, stringResource(R.string.tags_short_name), autoFocus = false, maxChars = 24) { save() }
            Text(stringResource(R.string.tags_short_name_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { save() }, enabled = clean != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Several tags become one: the name that stays is picked, the most used one at first. */
@Composable
internal fun MergeDialog(
    tags: List<String>,
    use: Map<String, Int>,
    styles: Map<String, TagStyle>,
    onDismiss: () -> Unit,
    onMerge: (String) -> Unit,
) {
    var into by remember { mutableStateOf(tags.maxBy { use[it] ?: 0 }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_merge_title)) },
        text = {
            Column {
                Text(stringResource(R.string.tags_merge_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(tags, key = { it }) { tag ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(tag == into, role = Role.RadioButton) { into = tag },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = tag == into, onClick = null)
                            TagDot(tagColor(tag, styles[tag]))
                            Text(tag, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            CountMark(use[tag] ?: 0)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(into) }) { Text(stringResource(R.string.tags_merge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Delete one tag or several, after a question; the notes stay. */
/** Before the notes of the chosen tags go to the archive: how many, and that the tags stay on them. */
@Composable
internal fun ArchiveDialog(
    tags: List<String>,
    notes: Int,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_archive_title, notes)) },
        text = { Text(stringResource(R.string.tags_archive_text, tags.joinToString(", ")), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton(onClick = onArchive) { Text(stringResource(R.string.action_archive)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun DeleteDialog(
    tags: List<String>,
    notes: Int,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tags.size == 1) stringResource(R.string.tags_delete_title, tags.single()) else stringResource(R.string.tags_delete_many_title, tags.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.size > 1) Text(tags.joinToString(", "), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (tags.size == 1) R.string.tags_delete_text else R.string.tags_delete_many_text, notes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
