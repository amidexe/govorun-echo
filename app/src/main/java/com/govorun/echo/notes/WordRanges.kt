package com.govorun.echo.notes

/**
 * The words of [text] where `Regex("\\S+").findAll(text)` finds them on Android, the runs between white space, found in one pass.
 * `findAll` hands the whole text to ICU again at every match, so its cost grows with the square of the text: on a one-hour lecture
 * (7.8k words, 50k characters) every call took 0.4 s on the emulator.
 */
internal fun wordRanges(text: String): List<IntRange> = runs(text) { !isWhiteSpace(it) }

/** The runs of letters and digits in [text] where `Regex("[\\p{L}\\p{N}]+").findAll(text)` finds them, in one pass as [wordRanges]. */
internal fun letterDigitRanges(text: String): List<IntRange> = runs(text) { isLetterOrNumber(it) }

/** The runs of [text] whose every code point [inside] takes, as the ranges of their chars. */
private inline fun runs(
    text: String,
    inside: (Int) -> Boolean,
): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var start = -1
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        if (!inside(c)) {
            if (start >= 0) runs += start until i
            start = -1
        } else if (start < 0) {
            start = i
        }
        i += Character.charCount(c)
    }
    if (start >= 0) runs += start until text.length
    return runs
}

/**
 * Unicode's White_Space, which is `\s` in Android's regex (ICU). Not [Char.isWhitespace] or [Character.isSpaceChar]: on Android
 * both also take U+180E, and the first U+001C-U+001F but not U+0085.
 */
private fun isWhiteSpace(c: Int) =
    c == 0x20 || c in 0x09..0x0D || c == 0x85 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000

/** `[\p{L}\p{N}]`: a letter, or a number of any kind (a digit, «Ⅻ», «²», «½»). */
private fun isLetterOrNumber(c: Int) =
    when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }
