package com.govorun.echo.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * The order of what a search finds: by how well a note answers, not by its date. Words are weighed by BM25 from the index's own
 * counts (FTS4 `matchinfo`): a rare word weighs more than a common one, a note holding a word several times more than one holding it
 * once, a long note a little less for each word. Measured on the tuning semester (88 questions) this puts the right note among the
 * first three for far more questions than the date does.
 */
internal object Ranking {
    private const val K1 = 1.2
    private const val B = 0.75

    /** What `matchinfo(words, 'pcnalx')` gives for a row, as numbers: SQLite writes them as 32-bit integers in the phone's own byte order. */
    fun ints(blob: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
        return IntArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * BM25 of a row for one word of a request, from its `matchinfo(…, 'pcnalx')`: the word's phrases (more than one when it is cut
     * into several, as «27,5»), one column, the rows of the table and their mean length, this row's length, and for every phrase its
     * hits in this row, in all rows and the rows holding it.
     */
    fun bm25(info: IntArray): Double {
        val phrases = info[0]
        val columns = info[1]
        val rows = info[2]
        val mean = info[3].coerceAtLeast(1).toDouble()
        val length = info[3 + columns].toDouble()
        val x = 3 + 2 * columns
        var score = 0.0
        for (p in 0 until phrases) {
            val at = x + 3 * p * columns
            val hits = info[at]
            if (hits == 0) continue
            val holding = info[at + 2]
            val rarity = ln(1 + (rows - holding + 0.5) / (holding + 0.5))
            score += rarity * hits * (K1 + 1) / (hits + K1 * (1 - B + B * length / mean))
        }
        return score
    }
}
