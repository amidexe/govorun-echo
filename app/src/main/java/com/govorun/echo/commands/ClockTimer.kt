package com.govorun.echo.commands

import android.content.Intent
import android.provider.AlarmClock

internal data class ClockTimer(val seconds: Int, val label: String) {
    fun intent(): Intent = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        .apply { if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }

    companion object {
        private val duration = Regex(
            """^(?:на\s+)?(\d+|[а-яё]+(?:\s+[а-яё]+)?)\s+(сек(?:унд[ауы]?)?|мин(?:ут[ауы]?)?)(?=$|[\s,.:!?—-])""",
            RegexOption.IGNORE_CASE,
        )
        private val small = listOf(
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
            "семнадцать", "восемнадцать", "девятнадцать",
        )
        private val tens = listOf("двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")

        fun parse(text: String): ClockTimer? {
            val phrase = text.trim()
            val match = duration.find(phrase) ?: return null
            val amount = amount(match.groupValues[1].lowercase()) ?: return null
            val multiplier = if (match.groupValues[2].startsWith("мин", ignoreCase = true)) 60 else 1
            if (amount !in 1..86_400 / multiplier) return null
            val label = phrase.substring(match.range.last + 1).trim(' ', '\t', ',', '.', ':', '!', '?', '—', '-')
            if (duration.containsMatchIn(label.removePrefix("и "))) return null
            return ClockTimer(amount * multiplier, label)
        }

        fun amount(value: String): Int? {
            value.toIntOrNull()?.let { return it }
            val words = value.split(Regex("\\s+"), limit = 2)
            if (words.size == 1) {
                unit(words.first())?.let { return it }
            }
            val ten = tens.indexOf(words.first()).takeIf { it >= 0 } ?: return null
            val remainder = words.getOrNull(1)?.let { unit(it)?.takeIf { number -> number in 1..9 } ?: return null } ?: 0
            return (ten + 2) * 10 + remainder
        }

        private fun unit(word: String): Int? = when (word) {
            "одна", "одну" -> 1
            "две" -> 2
            else -> small.indexOf(word).takeIf { it >= 0 }
        }
    }
}
