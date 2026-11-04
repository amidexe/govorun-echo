package com.govorun.echo.notes

/**
 * Russian words without their endings, so a search finds «лекцию» by «лекции» and «курицу» by «курица»: the Snowball stemmer
 * (snowballstem.org/algorithms/russian) and the commonest words of speech, which a search passes over. Everything here takes text
 * in its [plain] form: lower case, «ё» as «е». No dictionary and nothing leaves the phone.
 */
object WordForms {
    fun plain(text: String): String = text.lowercase().replace('ё', 'е')

    /** The words of a request that carry its meaning; the common ones («что», «когда», «мне») count only when nothing else is typed. */
    fun meaningful(words: List<String>): List<String> = words.filter { it !in COMMON }.ifEmpty { words }

    /** The terms ([term]) of the commonest words of speech: a search never reaches them by a near word ([NearWords]). */
    fun commonTerms(): Set<String> = COMMON.mapTo(HashSet(), ::term)

    /** What a search looks for: the [plain] words typed, without the common ones when there are others. */
    fun request(query: String): List<String> = meaningful(plain(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() })

    /** A word as the index keeps it and a search compares it: its stem when it has letters only and a stem long enough to mean something, else as it is. */
    fun term(word: String): String = if (word.all { it.isLetter() }) stem(word).takeIf { it.length >= 3 } ?: word else word

    /** What is left of a word when its ending is cut off. */
    fun stem(word: String): String {
        val rv = word.indexOfFirst { it in VOWELS }.let { if (it < 0) return word else it + 1 }
        val r2 = region(word, region(word, 0))
        var w = word

        fun inRv(ending: String) = w.endsWith(ending) && w.length - ending.length >= rv

        // The longest ending of the group; one that must follow «а» or «я» fails the group when it does not.
        fun cut(group: Endings): Boolean {
            val ending = (group.afterAOrYa + group.plain).filter(::inRv).maxByOrNull { it.length } ?: return false
            if (ending in group.afterAOrYa) {
                val before = w.length - ending.length - 1
                if (before < rv || w[before] !in "ая") return false
            }
            w = w.dropLast(ending.length)
            return true
        }

        if (!cut(GERUND)) {
            cut(REFLEXIVE)
            if (cut(ADJECTIVE)) cut(PARTICIPLE) else if (!cut(VERB)) cut(NOUN)
        }
        if (inRv("и")) w = w.dropLast(1)
        DERIVATIONAL.firstOrNull { w.endsWith(it) && w.length - it.length >= r2 }?.let { w = w.dropLast(it.length) }
        val superlative = SUPERLATIVE.firstOrNull(::inRv)
        when {
            superlative != null -> {
                w = w.dropLast(superlative.length)
                if (inRv("нн")) w = w.dropLast(1)
            }
            inRv("нн") -> w = w.dropLast(1)
            inRv("ь") -> w = w.dropLast(1)
        }
        return w
    }

    /** Where the region after the first consonant that follows a vowel begins, looking from [from]. */
    private fun region(
        word: String,
        from: Int,
    ): Int {
        for (i in from + 1 until word.length) if (word[i] !in VOWELS && word[i - 1] in VOWELS) return i + 1
        return word.length
    }

    private class Endings(
        val plain: List<String>,
        val afterAOrYa: List<String> = emptyList(),
    )

    private const val VOWELS = "аеиоуыэюя"

    private fun words(text: String) = text.split(' ')

    private val GERUND = Endings(words("ив ивши ившись ыв ывши ывшись"), words("в вши вшись"))
    private val ADJECTIVE = Endings(words("ее ие ые ое ими ыми ей ий ый ой ем им ым ом его ого ему ому их ых ую юю ая яя ою ею"))
    private val PARTICIPLE = Endings(words("ивш ывш ующ"), words("ем нн вш ющ щ"))
    private val REFLEXIVE = Endings(words("ся сь"))
    private val VERB =
        Endings(
            words("ила ыла ена ейте уйте ите или ыли ей уй ил ыл им ым ен ило ыло ено ят ует уют ит ыт ены ить ыть ишь ую ю"),
            words("ла на ете йте ли й л ем н ло но ет ют ны ть ешь нно"),
        )
    private val NOUN =
        Endings(words("а ев ов ие ье е иями ями ами еи ии и ией ей ой ий й иям ям ием ем ам ом о у ах иях ях ы ь ию ью ю ия ья я"))
    private val SUPERLATIVE = words("ейше ейш")
    private val DERIVATIONAL = words("ость ост")

    // The commonest words of Russian speech, fillers included: they match nearly every note and mean nothing to a search.
    private val COMMON =
        words(
            "и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было вот от меня еще нет о из " +
                "ему теперь когда даже ну ли если уже или ни быть был него до вас нибудь опять уж вам ведь там потом себя ничего ей может " +
                "они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего раз тоже себе под будет ж тогда кто этот того " +
                "потому этого какой ним здесь этом один почти мой тем чтобы нее сейчас были куда зачем всех можно при об хоть после над " +
                "больше тот через эти нас про всего них какая много разве эту моя свою этой перед том такой им более всегда конечно всю " +
                "между это эта мои мое короче типа значит вообще просто блин ладно ага угу вроде какие какое каких сколько который которые " +
                "которая очень нужно будем буду также либо ой эх алло",
        ).toSet()
}

/**
 * What a search looks for, and the one rule by which a word of a note answers it, for the list, the passage on a card and the note's
 * own screen alike: the same word in another form (the same [WordForms.term]: «лекции» and «лекцию»), or the same number. A piece of
 * a word is no answer: «кот» is not «который», «Лена» not «лень». The last word may still be being typed: when nothing holds it whole
 * ([open]), it answers the words it begins, from four letters on, so «бэка» finds «бэкапы».
 */
data class SearchWords(
    val query: String,
    val open: Boolean = false,
    /** For each word of the request, the near terms the index also looked for ([NearWords]): a slip, or a longer form of a name. */
    val near: List<Set<String>> = emptyList(),
) {
    val words: List<String> = WordForms.request(query)
    private val terms: List<Set<String>> = words.mapIndexed { i, word -> NoteIndex.wordsOf(word).mapTo(HashSet(), WordForms::term) + near.getOrElse(i) { emptySet() } }
    private val begun: String? = words.lastOrNull()?.takeIf { open && it.length >= BEGUN && it.all(Char::isLetter) }

    /** Which word of the request [word] answers (a word of a note in [WordForms.plain] form), or -1. */
    fun answers(word: String): Int {
        val term = WordForms.term(word)
        for (i in words.indices) if (term in terms[i]) return i
        return if (begun != null && word.startsWith(begun)) words.lastIndex else -1
    }

    companion object {
        /** How many letters a word still being typed needs before it answers the words it begins. */
        const val BEGUN = 4

        /** For one [text] (in [WordForms.plain] form) with no index to ask: the last word counts as being typed when no word of the text has it whole. */
        fun of(
            query: String,
            text: String,
        ): SearchWords {
            val whole = SearchWords(query)
            if (whole.words.isEmpty() || NoteIndex.wordsOf(text).any { whole.answers(it) == whole.words.lastIndex }) return whole
            return SearchWords(query, open = true)
        }
    }
}
