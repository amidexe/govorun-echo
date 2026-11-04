package com.govorun.echo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.adamglin.phosphoricons.fill.PushPin as PinnedPushPin

/*
 * The pieces of the one tag sheet ([TagShelf]): finding a tag by what is typed, the field on top, the line of a tag. The field only
 * finds; a name no tag has gets «Создать тег» as the last line of what it found. The sheet sets its text to onSurface: left to
 * Material, a sheet on surfaceContainerLow takes onSurfaceVariant, as our light scheme gives surfaceVariant the same colour, and the
 * names would be as grey as the counts.
 */

/** Where what is typed stands in a tag's name: [rank] 0 from its start, 1 from the start of one of its words, 2 inside a word. */
internal data class TagHit(
    val rank: Int,
    val start: Int,
    val length: Int,
)

/** How a tag answers what is typed, whatever the case, «ё» read as «е», a leading «#» left out; null when it does not. */
internal fun tagHit(
    tag: String,
    query: String,
): TagHit? {
    val typed = searchForm(query.trim().trimStart('#').trim())
    val name = searchForm(tag)
    var at = name.indexOf(typed)
    // «рецепты» for the tag «рецепт»: not inside its name, but the same word in another form.
    if (at < 0) {
        val term = com.govorun.echo.notes.WordForms.term(typed)
        return if (typed.isNotEmpty() && name.split(' ', '-', '_').any { it.isNotEmpty() && com.govorun.echo.notes.WordForms.term(it) == term }) TagHit(1, 0, 0) else null
    }
    if (at == 0) return TagHit(0, 0, typed.length)
    val first = at
    while (at >= 0) {
        if (!name[at - 1].isLetterOrDigit()) return TagHit(1, at, typed.length)
        at = name.indexOf(typed, at + 1)
    }
    return TagHit(2, first, typed.length)
}

/** The tags that answer what is typed, best first (from the start of the name, of one of its words, then inside one), in their own order within each. */
internal fun searchTags(
    tags: List<String>,
    query: String,
    aliasOf: (String) -> String = { "" },
): List<String> = tags.mapNotNull { tag -> (tagHit(tag, query) ?: aliasOf(tag).takeIf { it.isNotBlank() }?.let { tagHit(it, query) })?.let { tag to it.rank } }.sortedBy { it.second }.map { it.first }

/** The tag with exactly this name, whatever the case and «ё»: «елка» is the «Ёлка» already there, not a new tag. */
internal fun sameTag(
    tags: Collection<String>,
    name: String,
): String? {
    val typed = cleanTag(name)?.let(::searchForm) ?: return null
    return tags.firstOrNull { searchForm(it) == typed }
}

/** What ✓ on the keyboard does in a tag sheet. */
internal sealed interface TagEnter {
    /** Nothing typed, or several tags answer: only the keyboard goes away, nothing is guessed. */
    data object Hide : TagEnter

    /** The tag of exactly that name, or the only one that answers. */
    data class Pick(
        val tag: String,
    ) : TagEnter

    /** No tag answers: a new one of that name. */
    data class Create(
        val name: String,
    ) : TagEnter
}

/** ✓ never makes a tag of half a word: [all] tells whether the name is taken, [shown] (what the sheet lists) what answers. */
internal fun tagEnter(
    all: Collection<String>,
    shown: List<String>,
    query: String,
    aliasOf: (String) -> String = { "" },
): TagEnter {
    val name = cleanTag(query) ?: return TagEnter.Hide
    sameTag(all, name)?.let { return TagEnter.Pick(it) }
    val found = searchTags(shown, name, aliasOf)
    return when (found.size) {
        0 -> TagEnter.Create(name)
        1 -> TagEnter.Pick(found.single())
        else -> TagEnter.Hide
    }
}

/** The field on top of a tag sheet: it finds as it is typed and never makes a tag by itself; the keyboard opens only when it is tapped. */
@Composable
internal fun TagSearchField(
    value: String,
    onChange: (String) -> Unit,
    onEnter: () -> Unit,
    hint: String? = null,
) {
    // The same filled, rounded field as everywhere in Echo: no outline, the card colour, Phosphor icons at the usual size.
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_TAG)) },
        singleLine = true,
        placeholder = { Text(hint ?: stringResource(R.string.tags_find)) },
        leadingIcon = { Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = null, modifier = Modifier.size(ProductTokens.Size.ActiveIcon)) },
        trailingIcon =
            if (value.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onChange("") }) {
                        Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.tags_clear), modifier = Modifier.size(ProductTokens.Size.ActiveIcon))
                    }
                }
            },
        shape = RoundedCornerShape(ProductTokens.Radius.Card),
        colors =
            androidx.compose.material3.TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        modifier = Modifier.fillMaxWidth(),
        // A tag is found or named, not written: no autocorrection over it.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEnter() }),
    )
}

/** The tallest a sheet's list has been while the sheet is open, in pixels. */
@Stable
/** The title of a part of a tag sheet, in small spaced capitals, so it never reads as one more tag. */
@Composable
internal fun TagSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * One tag of the sheet: its colour, its name with the typed part lit, and [trailing] at the end (a count, a tick). [checked] puts a
 * box before it while tags are being chosen; [lit] is the tag whose notes the list shows. The whole line is the target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfLine(
    tag: String,
    color: Color,
    query: String,
    lit: Boolean,
    checked: Boolean?,
    onClick: () -> Unit,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ProductTokens.Size.Row)
            .clip(RoundedCornerShape(ProductTokens.Radius.Md))
            .background(if (lit || checked == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(role = role, onLongClickLabel = onLongClick?.let { stringResource(R.string.tags_choose) }, onLongClick = onLongClick, onClick = onClick)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Chosen, the dot turns into the round tick of a chosen note (as a letter turns into a tick in Gmail), so names stay put.
        if (checked == true) com.product.ui.ChoiceMark(true) else TagDot(color)
        TagName(tag, query, Modifier.weight(1f))
        trailing()
    }
}

/** The last line of a search no tag answers exactly: it makes the tag, in the colour its dot shows. */
@Composable
internal fun CreateTagLine(
    name: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ProductTokens.Size.Row)
            .clip(RoundedCornerShape(ProductTokens.Radius.Md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(PhosphorIcons.Regular.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.tags_create_tag), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagDot(color)
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A sheet with no tag yet: the field is how the first one is made. */
@Composable
internal fun NoTags() =
    Text(
        stringResource(R.string.tags_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
    )

@Composable
internal fun TagDot(color: Color) =
    // In a place as wide as the choice mark, so a chosen tag's tick stands where its dot did and the names stay in one column.
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) }



/** A tag's name with the typed part in the accent colour (weights of our variable Geist do not show yet, so not in bold). */
@Composable
private fun TagName(
    tag: String,
    query: String,
    modifier: Modifier,
) {
    // The lit part is placed by the search form of the name, which keeps its length for nearly every name; one that does not stays plain.
    val hit = if (query.isBlank()) null else tagHit(tag, query)?.takeIf { it.length > 0 && searchForm(tag).length == tag.length }
    val text =
        if (hit == null) {
            AnnotatedString(tag)
        } else {
            buildAnnotatedString {
                append(tag.substring(0, hit.start))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(tag.substring(hit.start, hit.start + hit.length)) }
                append(tag.substring(hit.start + hit.length))
            }
        }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
