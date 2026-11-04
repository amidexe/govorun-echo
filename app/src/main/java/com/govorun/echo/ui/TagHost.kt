package com.govorun.echo.ui

import android.content.Context
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore

/** The tags as the screens see them at one moment. */
internal class TagBook(
    /** Every tag: the pinned ones in the order of the row first, then the recently used, then by name. */
    val all: List<String>,
    /** Tags made by hand; they stay with no notes until deleted. */
    val made: Set<String>,
    val styles: Map<String, TagStyle>,
    /** The tags of the row above the list, in its order. */
    val pinned: List<String>,
    /** How many notes carry each tag, archived ones too. */
    val use: Map<String, Int>,
)

/**
 * The book from the notes and the tag settings. A tag exists while a note carries it or it was made by hand; a pinned one counts as
 * made, so it keeps its place in the row with no notes. A pin left from a deleted tag is ignored. The tags that are not pinned go
 * by their last use, the later of the newest note carrying the tag and the moment it was last put on a note or made ([used]), as the
 * next note is most often tagged like the last ones; then by how many notes carry them, then by name.
 */
internal fun tagBook(
    notes: List<NoteStore.Entry>,
    made: Set<String>,
    styles: Map<String, TagStyle>,
    order: List<String>,
    used: Map<String, Long> = emptyMap(),
): TagBook {
    val use = notes.flatMap { it.tags }.groupingBy { it }.eachCount()
    val newest = HashMap<String, Long>()
    notes.forEach { note -> note.tags.forEach { tag -> newest[tag] = maxOf(newest[tag] ?: 0L, note.startedAtMs) } }
    val known = use.keys + made
    val pinned = known.filter { styles[it]?.pinned == true }.sortedWith(compareBy<String> { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.lowercase() })
    val rest =
        (known - pinned.toSet()).sortedWith(
            compareByDescending<String> { maxOf(newest[it] ?: 0L, used[it] ?: 0L) }.thenByDescending { use[it] ?: 0 }.thenBy { it.lowercase() },
        )
    return TagBook(pinned + rest, made, styles, pinned, use)
}

/** How many of the chosen notes carry a tag. */
internal enum class TagShare { All, Some, None }

internal fun tagShare(
    notes: List<NoteStore.Entry>,
    tag: String,
): TagShare {
    val carrying = notes.count { tag in it.tags }
    return when {
        carrying == 0 -> TagShare.None
        carrying == notes.size -> TagShare.All
        else -> TagShare.Some
    }
}

/** What the tag controls do to the notes on disk and to the settings. */
internal object TagHost {
    fun read(
        context: Context,
        notes: List<NoteStore.Entry>,
    ): TagBook {
        val styles = AppSettings.tagStyles(context)
        val aliases = AppSettings.tagAliases(context)
        return tagBook(
            notes,
            AppSettings.tags(context),
            (styles.keys + aliases.keys).associateWith { tag -> styles[tag].let { TagStyle(it?.first, it?.second == true, aliases[tag].orEmpty()) } },
            AppSettings.tagOrder(context),
            AppSettings.tagUse(context),
        )
    }

    /** A tag made by hand, with its colour; it counts as just used. */
    fun create(
        context: Context,
        name: String,
        color: Int,
    ) {
        AppSettings.addTag(context, name)
        AppSettings.setTagStyle(context, name, color, AppSettings.tagStyles(context)[name]?.second == true)
        used(context, name)
    }

    /** A tag on the notes or off them; one put on goes to the top of the tags that are not pinned. */
    fun set(
        context: Context,
        ids: List<String>,
        tag: String,
        add: Boolean,
    ) {
        NoteStore.setTag(NoteStore.root(context), ids, tag, add)
        if (add) used(context, tag)
    }

    /** A tag just put on notes or made. */
    fun used(
        context: Context,
        tag: String,
        at: Long = System.currentTimeMillis(),
    ) = AppSettings.setTagUse(context, AppSettings.tagUse(context) + (tag to at))

    /** A colour keeps the tag where it is: pinned or not. */
    fun recolour(
        context: Context,
        tag: String,
        color: Int?,
    ) = AppSettings.setTagStyle(context, tag, color, AppSettings.tagStyles(context)[tag]?.second == true)

    /** Puts a tag at the end of the row above the list, or takes it out; a pinned tag stays there even with no notes. */
    /** Pinned tags at most: the row above the list stays a row, not a second list. */
    const val PIN_LIMIT = 5

    fun pin(
        context: Context,
        tag: String,
        on: Boolean,
    ) {
        if (on && AppSettings.tagStyles(context).count { it.value.second && it.key != tag } >= PIN_LIMIT) {
            android.widget.Toast.makeText(context, context.getString(com.govorun.echo.R.string.tags_pin_full, PIN_LIMIT), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        AppSettings.setTagStyle(context, tag, AppSettings.tagStyles(context)[tag]?.first, on)
        if (on) {
            AppSettings.addTag(context, tag)
            AppSettings.setTagOrder(context, AppSettings.tagOrder(context) - tag + tag)
        }
    }

    /** The row's new order after a drag; pinned tags the row did not show (the archive hides empty ones) keep their places after it. */
    fun reorder(
        context: Context,
        shown: List<String>,
        pinned: List<String>,
    ) = AppSettings.setTagOrder(context, shown + (pinned - shown.toSet()))

    /** The tag off every note and out of the settings; the notes stay. */
    fun delete(
        context: Context,
        notes: List<NoteStore.Entry>,
        tag: String,
    ) {
        NoteStore.setTag(NoteStore.root(context), notes.filter { tag in it.tags }.map { it.id }, tag, false)
        AppSettings.removeTag(context, tag)
        AppSettings.removeTagStyle(context, tag)
        AppSettings.setTagAlias(context, tag, "")
        AppSettings.setTagOrder(context, AppSettings.tagOrder(context) - tag)
        AppSettings.setTagUse(context, AppSettings.tagUse(context) - tag)
    }

    /** A new name on every note that has the tag and in the settings; a name another tag already has makes the two one. */
    fun rename(
        context: Context,
        notes: List<NoteStore.Entry>,
        from: String,
        to: String,
        shortName: String? = null,
    ) {
        if (from == to) { shortName?.let { AppSettings.setTagAlias(context, to, it) }; return }
        // A colour that comes from the name is written down first, so a new name does not repaint the tag.
        val styles = AppSettings.tagStyles(context)
        val taken = notes.any { to in it.tags } || to in AppSettings.tags(context)
        listOfNotNull(from, to.takeIf { taken }).forEach { tag ->
            val style = styles[tag]
            if (style?.first == null) AppSettings.setTagStyle(context, tag, tagColorIndex(tag, null), style?.second == true)
        }
        NoteStore.renameTag(NoteStore.root(context), notes.filter { from in it.tags }.map { it.id }, from, to)
        AppSettings.renameTag(context, from, to)
        shortName?.let { AppSettings.setTagAlias(context, to, it) }
    }
}
