package com.govorun.echo.notes

/**
 * Word times worked out from the phrase alone, for when Govorun gives none (an older Govorun) or its words do not match the
 * punctuated text: the phrase time is shared between the words by their length, and a comma or a full stop adds a small pause
 * to the word before it. Good enough for the highlight to follow the speech word by word instead of lighting the whole phrase.
 */
internal fun estimatedWords(
    text: String,
    startMs: Long,
    endMs: Long,
): List<NoteStore.Word> {
    val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty() || endMs <= startMs) return emptyList()
    val weights = tokens.map { token -> maxOf(token.count { it.isLetterOrDigit() }, 1) + if (token.last() in ",;:") 2 else if (token.last() in ".?!…") 4 else 0 }
    val total = weights.sum().toDouble()
    var cursor = 0.0
    return tokens.mapIndexed { i, token ->
        val from = startMs + ((endMs - startMs) * cursor / total).toLong()
        cursor += weights[i]
        val to = if (i == tokens.lastIndex) endMs else startMs + ((endMs - startMs) * cursor / total).toLong()
        NoteStore.Word(from, to, token)
    }
}
