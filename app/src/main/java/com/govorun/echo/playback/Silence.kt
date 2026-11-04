package com.govorun.echo.playback

/**
 * "Skip pauses" moves through a note by its text: the recognised phrases are played and the gaps between them are skipped.
 * Where speech is decides Govorun (its speech detector and recognition), not a loudness rule of our own; a note without text has
 * no such mode. [keepMs] after a phrase and [leadMs] before the next one are still played, so no word is clipped, and a gap
 * shorter than [minGapMs] is kept so the speech does not sound chopped. A quiet start before the first phrase and the rest after
 * the last one are skipped too.
 */
internal fun pausesBetween(
    phrases: List<LongRange>,
    totalMs: Long,
    keepMs: Long = 250L,
    leadMs: Long = 200L,
    minGapMs: Long = 700L,
): List<LongRange> {
    if (phrases.isEmpty() || totalMs <= 0L) return emptyList()
    // Phrases may overlap or come out of order (a continued note, estimated times): merge them into spans of speech first.
    val spoken = mutableListOf<LongRange>()
    for (phrase in phrases.sortedBy { it.first }) {
        val last = spoken.lastOrNull()
        if (last != null && phrase.first <= last.last) spoken[spoken.lastIndex] = last.first..maxOf(last.last, phrase.last) else spoken += phrase
    }
    val pauses = mutableListOf<LongRange>()
    var playedUntil = 0L
    var first = true
    for (span in spoken) {
        val gapStart = if (first) 0L else playedUntil
        if (span.first - gapStart >= minGapMs) {
            val from = if (first) 0L else gapStart + keepMs
            val to = span.first - leadMs
            if (to > from) pauses += from until to
        }
        playedUntil = span.last
        first = false
    }
    if (totalMs - playedUntil >= minGapMs) {
        val from = playedUntil + keepMs
        if (totalMs > from) pauses += from until totalMs
    }
    return pauses
}
