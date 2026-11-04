package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.govorun.echo.R

/**
 * A fair copy is Markdown, so it goes into Telegram or Obsidian as it is; on the note's screen the little of it Govorun writes is
 * drawn: paragraphs, `## headings`, `- ` and `1. ` lists, **bold** and *italic*. Anything else shows as written.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal object FairMarkdown {
    sealed interface Block {
        data class Heading(val text: String) : Block

        data class Item(val mark: String, val text: String) : Block

        data class Paragraph(val text: String) : Block
    }

    // Geist is one variable file declared for every weight, so a weight alone draws it regular; bold says its axis outright.
    internal val strong = FontFamily(Font(R.font.geist_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(640))))
    private val numbered = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
    internal val emphasis = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![*\p{L}\p{N}\p{M}_])\*(?!\s)(.+?)(?<!\s)\*(?![*\p{L}\p{N}\p{M}_])""")

    /** The blocks of [text]: lines of one paragraph are joined, a blank line ends it, a heading or list line stands alone. */
    fun blocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun close() {
            if (paragraph.isNotBlank()) out += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            val number = numbered.find(line)
            when {
                line.isEmpty() -> close()
                line.startsWith("#") && line.trimStart('#').startsWith(" ") -> { close(); out += Block.Heading(line.trimStart('#').trim()) }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> { close(); out += Block.Item("•", line.drop(2).trim()) }
                number != null -> { close(); out += Block.Item(number.groupValues[1] + ".", number.groupValues[2].trim()) }
                else -> paragraph.append(if (paragraph.isEmpty()) line else " $line")
            }
        }
        close()
        return out
    }

    /** [text] with its **bold** and *italic* drawn and their marks taken away. */
    fun inline(text: String): AnnotatedString =
        buildAnnotatedString {
            var at = 0
            for (match in emphasis.findAll(text)) {
                append(text.substring(at, match.range.first))
                val bold = match.groups[1]?.value ?: match.groups[2]?.value
                if (bold != null) {
                    withStyle(SpanStyle(fontFamily = strong, fontWeight = FontWeight.SemiBold)) { append(bold) }
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groups[3]!!.value) }
                }
                at = match.range.last + 1
            }
            append(text.substring(at))
        }
}

@Composable
internal fun FairMarkdownText(text: String) {
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FairMarkdown.blocks(text).forEach { block ->
            when (block) {
                is FairMarkdown.Block.Heading -> Text(FairMarkdown.inline(block.text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                is FairMarkdown.Block.Paragraph -> Text(FairMarkdown.inline(block.text), style = body)
                is FairMarkdown.Block.Item ->
                    Row {
                        Text(block.mark, style = body, modifier = Modifier.width(22.dp))
                        Text(FairMarkdown.inline(block.text), style = body)
                    }
            }
        }
    }
}
