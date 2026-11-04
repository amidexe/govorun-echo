package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.product.ui.ProductTokens

/** How a tag looks and where it sits: one of a few colours, and whether it is pinned to the row above the list. */
internal data class TagStyle(
    val color: Int? = null,
    val pinned: Boolean = false,
    val shortName: String = "",
)

internal fun tagDisplayName(tag: String, style: TagStyle?) = style?.shortName?.takeIf { it.isNotBlank() } ?: tag

internal val TAG_COLORS =
    listOf(0xFFE8833A, 0xFFE6B422, 0xFF5CB85C, 0xFF2FB5A8, 0xFF4A90E2, 0xFF8E6BD8, 0xFFE0609A, 0xFFE5534B).map { Color(it) }

/** A tag's place in the palette: its own colour, or a steady one from its name, so it looks the same every time. */
internal fun tagColorIndex(
    tag: String,
    style: TagStyle?,
): Int = (style?.color ?: (tag.hashCode() and Int.MAX_VALUE)) % TAG_COLORS.size

internal fun tagColor(
    tag: String,
    style: TagStyle?,
): Color = TAG_COLORS[tagColorIndex(tag, style)]

/**
 * The row above the list: a fixed tag browser and «Все», then horizontally scrolling pinned tags as small pills with a
 * coloured dot. A tap on a pill shows only those notes, another tap shows all again. Press and hold a pill to drag it left or right
 * into the order you want; hold and let go without moving to open the tag sheet with that tag chosen (colour, pin, name, delete). A tag, a colour or «Без
 * тегов» chosen under «+» stands right after «Все» with a cross while it filters. During a search a pill shows how many notes matched,
 * when any did.
 */
@Composable
internal fun TagChips(
    pinned: List<Pair<String, Int>>,
    filter: NotesFilter,
    showCounts: Boolean,
    styles: Map<String, TagStyle>,
    onFilter: (NotesFilter) -> Unit,
    onReorder: (List<String>) -> Unit,
    onEdit: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    val counts = pinned.toMap()
    var order by remember(pinned.map { it.first }) { mutableStateOf(pinned.map { it.first }) }
    val widths = remember { mutableStateMapOf<String, Int>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { PILL_GAP.toPx() }
    val none = filter.withoutTags().copy(untagged = filter.untagged)
    val colourNames = stringArrayResource(R.array.tag_color_names)
    // Every pill stands centred in the same touch height, so the line is even whether a pill takes its touch itself or not.
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(PILL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The browser and «Все» stay reachable even when the chosen pinned tag is far along the row.
        // A tag's own icon opens every tag: a «+» read as «add a tag» to this list, which it never did.
        TagPill("", false, icon = PhosphorIcons.Regular.Tag, description = stringResource(R.string.tags_browse), onClick = onBrowse)
        // "All" is lit when nothing is chosen, so it is clear whether the list is filtered.
        TagPill(stringResource(R.string.tags_all), filter.noTag) { onFilter(none) }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PILL_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // What was chosen under «+» and has no pill in the row stays in sight while it filters; its cross shows all again.
            val chosen =
                when {
                    filter.tag != null && filter.tag !in order -> filter.tag to tagColor(filter.tag, styles[filter.tag])
                    else -> null
                }
            chosen?.let { (label, dot) ->
                val reveal = remember { BringIntoViewRequester() }
                LaunchedEffect(label, widths[label]) { if (widths[label] != null) reveal.bringIntoView() }
                Box(Modifier.bringIntoViewRequester(reveal).onSizeChanged { widths[label] = it.width }) {
                    TagPill(tagDisplayName(label, styles[label]), true, description = label, dot = dot, trailing = PhosphorIcons.Regular.X) { onFilter(none) }
                }
            }
            order.forEach { tag ->
                key(tag) {
                    val reveal = remember { BringIntoViewRequester() }
                    LaunchedEffect(filter.tag, widths[tag], order) {
                        if (tag == filter.tag && widths[tag] != null) reveal.bringIntoView()
                    }
                    val select by rememberUpdatedState { onFilter(if (tag == filter.tag) none else none.copy(tag = tag, untagged = false)) }
                    val edit by rememberUpdatedState { onEdit(tag) }
                    val reorder by rememberUpdatedState(onReorder)
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .bringIntoViewRequester(reveal)
                            .zIndex(if (dragging == tag) 1f else 0f)
                            .graphicsLayer { translationX = if (dragging == tag) dragX else 0f }
                            .onSizeChanged { widths[tag] = it.width }
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                onClick {
                                    select()
                                    true
                                }
                                onLongClick {
                                    edit()
                                    true
                                }
                            }
                            // One gesture for the pill: a tap filters, a hold opens the tag sheet with it chosen, a hold and a drag moves it. The pill itself
                            // takes no clicks, or it would take the finger lifted after a hold for a tap.
                            .pointerInput(tag) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val held = awaitLongPressOrCancellation(down.id)
                                    if (held == null) {
                                        // Let go before the hold: a tap, unless the row took the finger to scroll.
                                        if (currentEvent.changes.any { it.id == down.id && it.changedToUp() }) select()
                                        return@awaitEachGesture
                                    }
                                    dragging = tag
                                    dragX = 0f
                                    moved = false
                                    drag(held.id) { change ->
                                        // The shift is read before the change is taken: a taken change reports none.
                                        dragX += change.positionChange().x
                                        change.consume()
                                        val i = order.indexOf(tag)
                                        // Dragged past the middle of the neighbour: they trade places and the finger stays on the pill.
                                        if (dragX > 0 && i < order.lastIndex) {
                                            val w = (widths[order[i + 1]] ?: 0) + gap
                                            if (dragX > w / 2) {
                                                order = order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }
                                                dragX -= w
                                                moved = true
                                            }
                                        } else if (dragX < 0 && i > 0) {
                                            val w = (widths[order[i - 1]] ?: 0) + gap
                                            if (-dragX > w / 2) {
                                                order = order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }
                                                dragX += w
                                                moved = true
                                            }
                                        }
                                    }
                                    dragging = null
                                    dragX = 0f
                                    // Held and let go where it was: the tag's sheet; moved: the row keeps the new order.
                                    if (moved) reorder(order) else edit()
                                }
                            },
                    ) {
                        TagPill(tagDisplayName(tag, styles[tag]), tag == filter.tag, dot = tagColor(tag, styles[tag]), description = tag, onClick = null)
                    }
                }
            }
        }
    }
}

/** The palette of a tag: one tap picks the colour; a [chosen] outside the palette ticks none. */
@Composable
internal fun ColorRow(
    chosen: Int,
    onPick: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TAG_COLORS.forEachIndexed { index, swatch ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(swatch).clickable { onPick(index) }, contentAlignment = Alignment.Center) {
                if (index == chosen) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The field for a tag's new name, rounded like the cards: the keyboard opens at once with the cursor after the last letter. */
@Composable
internal fun TagField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    label: String,
    autoFocus: Boolean = true,
    maxChars: Int = MAX_TAG,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val raw = next.text
            val text = if (raw.codePointCount(0, raw.length) > maxChars) raw.substring(0, raw.offsetByCodePoints(0, maxChars)) else raw
            onChange(if (text == raw) next else next.copy(text = text, selection = TextRange(text.length)))
        },
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(ProductTokens.Radius.Md),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/**
 * A tag as a small pill: the card colour at rest, the accent container when it is on; a coloured dot or an icon before the name, or
 * the icon alone when there is no name. With no [onClick] the pill only shows itself and whoever holds it handles the touch.
 */
@Composable
internal fun TagPill(
    label: String,
    on: Boolean,
    dot: Color? = null,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    description: String? = null,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(ProductTokens.Radius.Xl)
    val color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val named = Modifier.semantics { selected = on; if (description != null) contentDescription = description }
    // Small enough for a row of them to stay a row: 30 dp high, the icon alone a circle of the same height.
    val bare = label.isEmpty()
    val body: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = if (bare) 7.dp else 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            if (!bare) Text(label, style = noteSupportingStyle(), maxLines = 1)
            if (trailing != null) Icon(trailing, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
    if (onClick == null) {
        Surface(modifier = named, shape = shape, color = color, contentColor = content, content = body)
    } else {
        Surface(onClick = onClick, modifier = named, shape = shape, color = color, contentColor = content, content = body)
    }
}

private val PILL_GAP = 6.dp

/** A pill's name with how many notes it holds: during a search, and only when it holds any, as a row of zeros says nothing. */
internal fun countedLabel(
    tag: String,
    count: Int,
    show: Boolean,
): String = if (show && count > 0) "$tag $count" else tag

internal const val MAX_TAG = 80

/** "  #Работа " becomes "Работа"; nothing left means no tag. */
internal fun cleanTag(raw: String): String? = raw.trim().trimStart('#').trim().takeIf { it.isNotEmpty() }

/** Whether a tag answers what is typed: any part of its name, whatever the case, «ё» read as «е», a leading «#» left out. */
internal fun tagMatches(
    tag: String,
    query: String,
): Boolean = searchForm(tag).contains(searchForm(query.trim().trimStart('#').trim()))

/** A name as the tag searches compare it: in small letters, «ё» as «е». */
internal fun searchForm(text: String) = text.lowercase().replace('ё', 'е')
