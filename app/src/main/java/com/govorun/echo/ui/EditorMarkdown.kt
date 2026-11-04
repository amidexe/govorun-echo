package com.govorun.echo.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Presentation only: the editor keeps complete Markdown for saving, recovery and undo. */
internal object EditorMarkdown : OutputTransformation, InputTransformation {
    data class Edit(val start: Int, val end: Int, val text: String = "")
    data class Emphasis(val start: Int, val end: Int, val bold: Boolean)
    data class Presentation(val text: String, val edits: List<Edit>, val emphasis: List<Emphasis>)

    private val headings = Regex("(?m)^[ \\t]*#+ ")
    private val bullets = Regex("(?m)^[ \\t]*([-*]) ")
    private val wholeHeading = Regex("([ \\t]*#+ )([^\\n]+)")

    /** Touch selection spans hidden delimiters too. Replacing its word should retain its style. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        val original = originalText.toString()
        val replacements = (0 until changes.changeCount).map { changes.getOriginalRange(it) to changes.getRange(it) }
        replacements.sortedByDescending { it.second.min }.forEach { (before, after) ->
            val old = original.substring(before.min, before.max)
            val value = asCharSequence().subSequence(after.min, after.max).toString()
            if (value.isEmpty() || '\n' in value) return@forEach
            val emphasis = FairMarkdown.emphasis.matchEntire(old)
            val heading = wholeHeading.matchEntire(old)
            val (prefix, suffix) = when {
                emphasis != null -> (if (emphasis.groups[1] != null || emphasis.groups[2] != null) 2 else 1).let { old.take(it) to old.takeLast(it) }
                heading != null -> heading.groupValues[1] to ""
                else -> return@forEach
            }
            val caret = selection.takeIf { it.min >= after.min && it.max <= after.max }
            replace(after.min, after.max, prefix + value + suffix)
            if (caret != null) selection = TextRange(caret.start + prefix.length, caret.end + prefix.length)
        }
    }

    /** Same supported emphasis as the reader; ordinary punctuation and unsupported syntax stay literal. */
    fun presentation(source: String): Presentation {
        val edits = mutableListOf<Edit>()
        val styles = mutableListOf<Emphasis>()
        FairMarkdown.emphasis.findAll(source).forEach { match ->
            val bold = match.groups[1] != null || match.groups[2] != null
            val width = if (bold) 2 else 1
            edits += Edit(match.range.first, match.range.first + width)
            edits += Edit(match.range.last + 1 - width, match.range.last + 1)
            styles += Emphasis(match.range.first + width, match.range.last + 1 - width, bold)
        }
        headings.findAll(source).forEach { match ->
            edits += Edit(match.range.first, match.range.last + 1)
            val end = source.indexOf('\n', match.range.last + 1).takeIf { it >= 0 } ?: source.length
            styles += Emphasis(match.range.last + 1, end, true)
        }
        bullets.findAll(source).forEach { match ->
            val marker = checkNotNull(match.groups[1]).range.first
            edits += Edit(marker, marker + 1, "•")
        }
        val ordered = edits.sortedByDescending { it.start }
        val visible = StringBuilder(source).apply { ordered.forEach { replace(it.start, it.end, it.text) } }.toString()
        fun position(raw: Int): Int = raw + edits.sumOf { if (it.end <= raw) it.text.length - (it.end - it.start) else 0 }
        return Presentation(visible, ordered, styles.map { it.copy(start = position(it.start), end = position(it.end)) })
    }

    override fun TextFieldBuffer.transformOutput() {
        val view = presentation(asCharSequence().toString())
        view.edits.forEach { replace(it.start, it.end, it.text) }
        view.emphasis.filter { it.start < it.end }.forEach {
            addStyle(if (it.bold) SpanStyle(fontFamily = FairMarkdown.strong, fontWeight = FontWeight.SemiBold) else SpanStyle(fontStyle = FontStyle.Italic), it.start, it.end)
        }
    }
}
