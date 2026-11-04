package com.govorun.echo.notes

/**
 * The words a search also tries for a word of the request: the endearing forms a name or a word grows into («Миша» → «Мишенька»,
 * «Мишка», «Мишаня»; «кот» → «котик»), and, when no note holds the word at all, a slip of the finger or of the recognition («рыпа»
 * for «рыба», «стамотолог» for «стоматолог»). Rules, not a list of names or words: a longer form is a term of the index
 * ([WordForms.term], stems) that is the request's term with a Russian diminutive suffix after it; a slip is a term that starts with
 * the same letter and differs by one slip of Russian spelling or recognition (two from eight letters on), for a stem of four letters
 * or more: shorter ones have too many neighbours («кот», «код», «кол»). A slip is a confusion people and recognition really make —
 * vowels that sound alike (а/о, е/и, и/ы, е/я, у/ю, е/ё), voiced and voiceless pairs (б/п, в/ф, г/к, д/т, ж/ш, з/с), a doubled
 * letter or a soft or hard sign put in or left out, two neighbours swapped — so «рыпа» finds «рыба» and «стамотолог» «стоматолог»,
 * while «готовил», which no note holds, does not find «голова»: т and л are no slip. The stems of the commonest words of speech are left out,
 * so «кот» never brings «который», and «котлета» is no form of «кот». The notes they find stand after the notes holding the word
 * itself, at half its weight.
 */
internal object NearWords {
    /**
     * Fewer notes than this holding a word whole, and the search tries its slips too: none. A word some note holds was typed right,
     * and its neighbours one letter away are other words («готовил» is not «голова»).
     */
    const val FEW = 1

    /** No more near words than this for one word of a request: the commonest of them, so a short stem cannot flood the list. */
    const val MOST = 12

    /** The shortest stem a slip is looked for: a shorter one has too many neighbours one letter away. */
    const val SLIP_FROM = 4

    /** The weight of a note found by a near word, against one holding the word itself. */
    const val WEIGHT = 0.5

    /** How Russian makes an endearing or small form of a name or a word, by what follows the stem: Мишенька, Танечка, Петюня, котик. */
    private val DIMINUTIVE = listOf("еньк", "оньк", "ечк", "очк", "ичк", "ушк", "юшк", "ышк", "уль", "юль", "ун", "юн", "ан", "ян", "ик", "чик", "ок", "ек", "к")

    private val common: Set<String> by lazy { WordForms.commonTerms() }

    /** Whether [other] is [term] with a diminutive suffix: a longer form of the same name or word. */
    fun isFormOf(
        other: String,
        term: String,
    ): Boolean = other.length > term.length && other.startsWith(term) && DIMINUTIVE.any { other.startsWith(it, term.length) }

    /**
     * The near terms of [term] among [vocabulary] (each term with the number of notes holding it), commonest first: its longer forms,
     * and its slips too when [slips]. None for a term of digits or shorter than three letters.
     */
    fun of(
        term: String,
        vocabulary: Map<String, Int>,
        slips: Boolean,
    ): List<String> {
        if (term.length < 3 || !term.all { it.isLetter() }) return emptyList()
        val limit = if (term.length >= 8) 2 else 1
        return vocabulary.entries
            .asSequence()
            .filter { (other, _) -> other != term && other[0] == term[0] && other !in common && other.all { it.isLetter() } }
            .filter { (other, _) -> isFormOf(other, term) || (slips && term.length >= SLIP_FROM && kotlin.math.abs(other.length - term.length) <= limit && slips(other, term, limit) <= limit) }
            .sortedByDescending { it.value }
            .take(MOST)
            .map { it.key }
            .toList()
    }

    /** Letters that stand for one another in a slip: vowels that sound alike, voiced and voiceless pairs. */
    private val ALIKE: List<Set<Char>> =
        listOf("ао", "еиэ", "иы", "ея", "ую", "её", "бп", "вф", "гк", "дт", "жш", "зс").map { it.toSet() }

    private fun alike(
        a: Char,
        b: Char,
    ): Boolean = a == b || ALIKE.any { a in it && b in it }

    /**
     * How many slips turn [a] into [b], counted no further than [limit] + 1: a letter changed for one that sounds alike, a doubled
     * letter or a soft or hard sign put in or taken out, two neighbours swapped. Anything else is no slip and counts past the limit.
     */
    fun slips(
        a: String,
        b: String,
        limit: Int,
    ): Int {
        val far = limit + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = if (i == 0) 0 else d[i - 1][0] + spare(a, i - 1)
        for (j in 1..b.length) d[0][j] = d[0][j - 1] + spare(b, j - 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                var best = d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else if (alike(a[i - 1], b[j - 1])) 1 else far
                best = minOf(best, d[i - 1][j] + spare(a, i - 1), d[i][j - 1] + spare(b, j - 1))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, d[i - 2][j - 2] + 1)
                d[i][j] = minOf(best, far)
            }
        }
        return d[a.length][b.length]
    }

    /** A letter of [word] at [at] that may be put in or left out as a slip: a soft or hard sign, or one of a doubled pair. */
    private fun spare(
        word: String,
        at: Int,
    ): Int {
        val c = word[at]
        val doubled = (at > 0 && word[at - 1] == c) || (at + 1 < word.length && word[at + 1] == c)
        return if (c == 'ь' || c == 'ъ' || doubled) 1 else 1_000
    }
}
