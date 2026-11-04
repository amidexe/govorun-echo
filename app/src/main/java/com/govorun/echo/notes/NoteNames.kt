package com.govorun.echo.notes

/** What a note is called where it gets one line (the compact list, the player's bar): the name its owner typed, else how it begins. */
internal fun NoteStore.Entry.shownTitle(): String = typedTitle().ifBlank { smartName() }.ifBlank { text.trim().substringBefore('\n') }

/**
 * The name given for the note: the one in its итоги, else its fair copy's from Govorun, else the card's, else its «Кратко»'s;
 * never over the owner's own.
 */
internal fun NoteStore.Entry.smartName(): String =
    if (typedTitle().isNotBlank()) {
        ""
    } else {
        itogi?.nazvanie?.takeIf { it.isNotBlank() } ?: fair?.name?.takeIf { it.isNotBlank() } ?: cardShown()?.name ?: brief?.name?.takeIf { it.isNotBlank() && it != NOISE_NAME }.orEmpty()
    }

/** The card the list shows for the note: the smart functions' one, unless it calls the note noise or the owner named it. */
internal fun NoteStore.Entry.cardShown(): NoteCard? = card?.takeIf { !it.none && it.name != NOISE_NAME && typedTitle().isBlank() }

/** What the former smart names called a recording with nothing to say: such a name is not shown. */
private const val NOISE_NAME = "Шум"

/** The name its owner typed. One made from the note's own words is no name (the owner, 22.10.2026); 0.1.54 wrote such names, see
 * [com.govorun.echo.notes.AutoTitles]. */
internal fun NoteStore.Entry.typedTitle(): String = if (titleAuto) "" else title

/**
 * What the note is about, for its card in the list: the owner's own words first, else the one its fair copy suggested, else an older
 * «Кратко»'s; empty without any.
 */
internal fun NoteStore.Entry.aboutLine(): String = about.ifBlank { itogi?.oChem?.takeIf { it.isNotBlank() } ?: fair?.about?.takeIf { it.isNotBlank() } ?: brief?.about.orEmpty() }
