package com.govorun.echo.commands

import com.govorun.echo.notes.letterDigitRanges

/** Finds the command that a spoken text starts with. */
object Matcher {
    data class Match(
        val command: Command,
        val trigger: String,
        val rest: String,
    )

    private fun normal(text: String) = text.lowercase().replace('ё', 'е')

    /**
     * The command whose phrase says the most (the longest phrase) among those the text fits; on a tie, the first
     * in the list. A command that wants the whole text only fits when nothing else was said; `*` after a word means
     * any ending of it.
     */
    fun find(
        text: String,
        commands: List<Command>,
    ): Match? {
        val words = letterDigitRanges(text)
        if (words.isEmpty()) return null
        val spoken = words.map { normal(text.substring(it)) }
        var best: Triple<Command, Int, Int>? = null
        for (command in commands) {
            for (phrase in command.phrases) {
                val tokens = Regex("[\\p{L}\\p{N}]+\\*?").findAll(normal(phrase)).map { it.value }.toList()
                if (tokens.isEmpty() || tokens.size > spoken.size) continue
                if (!command.prefix && tokens.size != spoken.size) continue
                val fits =
                    tokens.indices.all { i ->
                        val token = tokens[i]
                        if (token.endsWith("*")) spoken[i].startsWith(token.dropLast(1)) else spoken[i] == token
                    }
                if (fits && (best == null || tokens.size > best.third)) best = Triple(command, 0, tokens.size)
            }
        }
        val (command, _, length) = best ?: return null
        val last = words[length - 1]
        val rest = text.substring(last.last + 1).trim().trimStart(',', '.', ':', ';', '-', '—', ' ').trimEnd('.', ' ')
        return Match(command, text.substring(words[0].first, last.last + 1), rest)
    }
}
