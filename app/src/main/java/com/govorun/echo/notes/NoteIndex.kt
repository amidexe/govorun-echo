package com.govorun.echo.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The search index beside the note folders, in SQLite: a row for every note with the stamp of its files (the times and sizes of
 * its meta and text), and in a full-text table (FTS4) the stems of the words of its name and text. Tags are kept out of the index,
 * but a search reads them from the notes and lets them answer like words ([com.govorun.echo.ui.matchesQuery]), and offers them by name under the field. A search asks the index for the notes holding a word in any of its
 * forms instead of reading every note, and when a word finds few, for its near words too ([NearWords]). It is only a copy: the
 * folders stay the truth, a note is indexed again when its files change and dropped when it is gone, and a missing, damaged or
 * older index is made anew from the folders. Later it can hold the list's rows and the state of a sync.
 */
internal class NoteIndex private constructor(
    private val context: Context,
) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    /**
     * What the index answered for [query]: per word of the request ([WordForms.request]) the notes holding it, each with its BM25
     * for that word ([scores]), and which notes it knows as they are now.
     */
    class Found(
        val query: String,
        val scores: List<Map<String, Double>>,
        val covered: Set<String>,
        /** The words as they were looked for: whether the last one counted as still being typed. */
        val words: SearchWords = SearchWords(query),
        /** The notes only a near word found ([NearWords]): they stand after every note holding a word of the request itself. */
        val nearOnly: Set<String> = emptySet(),
    ) {
        val sets: List<Set<String>> get() = scores.map { it.keys }

        /** How well note [id] answers the words of the request: the sum of its BM25 for each word it holds. */
        fun score(id: String): Double = scores.sumOf { it[id] ?: 0.0 }
    }

    // A search reads while the index is being brought up to date.
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, stamp TEXT NOT NULL)")
        // A note's words live under the rowid of its row in `notes`, so they are found and dropped by key.
        db.execSQL("CREATE VIRTUAL TABLE words USING fts4(terms)")
        // Every term of the index with the number of notes holding it, read by FTS4 itself: the near words are looked for among them.
        db.execSQL("CREATE VIRTUAL TABLE vocabulary USING fts4aux(words)")
    }

    // A copy of the folders: another layout is simply made again from them.
    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = remake(db)

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = remake(db)

    private fun remake(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS notes")
        db.execSQL("DROP TABLE IF EXISTS vocabulary")
        db.execSQL("DROP TABLE IF EXISTS words")
        // The vectors of the search by meaning (index 2), which Echo no longer has.
        db.execSQL("DROP TABLE IF EXISTS pieces")
        onCreate(db)
    }

    private fun stampOf(id: String): String {
        val dir = NoteStore.noteDir(context, id)
        val meta = File(dir, "meta.json")
        val text = File(dir, "text.txt")
        return "${meta.lastModified()}:${meta.length()}:${text.lastModified()}:${text.length()}"
    }

    private fun stamps(db: SQLiteDatabase): Map<String, Pair<Long, String>> =
        HashMap<String, Pair<Long, String>>().also { known ->
            db.rawQuery("SELECT id, rowid, stamp FROM notes", null).use { c -> while (c.moveToNext()) known[c.getString(0)] = c.getLong(1) to c.getString(2) }
        }

    /** Brings the index in line with [notes], the list as read with each note's text: changed and new notes again, gone ones out. */
    @Synchronized
    fun update(notes: List<NoteStore.Entry>) {
        val db = writableDatabase
        val known = stamps(db)
        val stems = HashMap<String, String>()
        var changed = false
        db.beginTransaction()
        try {
            for (note in notes) {
                val stamp = stampOf(note.id)
                val old = known[note.id]
                if (old?.second == stamp) continue
                // A card's and a brief's words are the note's words too: the search finds it by them without the network.
                val terms = termsOf(note.title, note.text + " " + note.about + " " + note.card?.searchText.orEmpty() + " " + note.brief?.searchText.orEmpty() + note.fair?.let { " ${it.name} ${it.about} ${it.text}" }.orEmpty(), stems)
                if (old == null) {
                    val row = db.insertOrThrow("notes", null, ContentValues().apply { put("id", note.id); put("stamp", stamp) })
                    db.insertOrThrow("words", null, ContentValues().apply { put("docid", row); put("terms", terms) })
                } else {
                    db.update("notes", ContentValues().apply { put("stamp", stamp) }, "rowid = ?", arrayOf(old.first.toString()))
                    db.update("words", ContentValues().apply { put("terms", terms) }, "docid = ?", arrayOf(old.first.toString()))
                }
                changed = true
            }
            val present = notes.mapTo(HashSet()) { it.id }
            for ((id, row) in known) {
                if (id in present) continue
                db.delete("words", "docid = ?", arrayOf(row.first.toString()))
                db.delete("notes", "rowid = ?", arrayOf(row.first.toString()))
                changed = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            if (changed) vocabulary = null
        }
    }

    /** The terms of the index with the number of notes holding each, read once and again after the index changes. */
    @Volatile
    private var vocabulary: Map<String, Int>? = null

    private fun vocabulary(db: SQLiteDatabase): Map<String, Int> =
        vocabulary ?: HashMap<String, Int>().also { terms ->
            db.rawQuery("SELECT term, documents FROM vocabulary WHERE col = '*'", null).use { c -> while (c.moveToNext()) terms[c.getString(0)] = c.getInt(1) }
            vocabulary = terms
        }

    /** The notes holding each word of [query]; [notes] are the list as it is, to tell which of them the index knows as they are. */
    fun find(
        query: String,
        notes: List<NoteStore.Entry>,
    ): Found {
        val db = readableDatabase
        val known = stamps(db)
        val covered = notes.filter { known[it.id]?.second == stampOf(it.id) }.mapTo(HashSet()) { it.id }
        // Each word on its own, as the list matches them; FTS4 counts with it what BM25 needs (search.Ranking).
        fun scored(match: String?): Map<String, Double> {
            if (match == null) return emptyMap()
            return HashMap<String, Double>().also { ids ->
                db.rawQuery("SELECT notes.id, matchinfo(words, 'pcnalx') FROM words JOIN notes ON notes.rowid = words.docid WHERE words.terms MATCH ?", arrayOf(match)).use { c ->
                    while (c.moveToNext()) ids[c.getString(0)] = Ranking.bm25(Ranking.ints(c.getBlob(1)))
                }
            }
        }
        val whole = SearchWords(query)
        val scores = whole.words.map { scored(matchOf(it)) }
        // The last word, when no note holds it whole, is still being typed: it finds the words it begins.
        if (scores.isNotEmpty() && scores.last().isEmpty()) {
            val begun = SearchWords(query, open = true)
            val prefix = beginningOf(begun.words.last())
            if (prefix != null) {
                val typed = scored(prefix)
                if (typed.isNotEmpty()) return Found(query, scores.dropLast(1) + listOf(typed), covered, begun)
            }
        }
        // A word also finds its longer forms (Миша → Мишенька) and, when few notes hold it whole, its slips; those notes weigh half.
        val near = whole.words.mapIndexed { i, word ->
            matchOf(word)?.takeIf { ' ' !in it }?.let { NearWords.of(it, vocabulary(db), slips = scores[i].size < NearWords.FEW) } ?: emptyList()
        }
        if (near.all { it.isEmpty() }) return Found(query, scores, covered, whole)
        val widened = scores.mapIndexed { i, own ->
            HashMap(own).also { all -> near[i].forEach { term -> scored(term).forEach { (id, score) -> if (id !in own) all.merge(id, score * NearWords.WEIGHT, ::maxOf) } } }
        }
        val holding = scores.flatMapTo(HashSet()) { it.keys }
        val nearOnly = widened.flatMapTo(HashSet()) { it.keys }.apply { removeAll(holding) }
        return Found(query, widened, covered, SearchWords(query, near = near.map { it.toSet() }), nearOnly)
    }

    companion object {
        private const val NAME = "index.db"
        // 3: tags left the words, the vectors of the search by meaning left the index.
        private const val VERSION = 3

        @Volatile private var instance: NoteIndex? = null

        fun get(context: Context): NoteIndex = instance ?: synchronized(this) { instance ?: NoteIndex(context.applicationContext).also { instance = it } }

        /** The stems of the words of a note's name and text, each once ([stems] remembers the stems already worked out). */
        fun termsOf(
            title: String,
            text: String,
            stems: MutableMap<String, String> = HashMap(),
        ): String = wordsOf(WordForms.plain("$title $text")).map { word -> stems.getOrPut(word) { WordForms.term(word) } }.distinct().joinToString(" ")

        /**
         * The words of [text], each once: its runs of letters and digits, found in one pass. A regex is no good here: on Android
         * every match of `findAll` hands the whole text to ICU again, so a lecture took a second instead of a millisecond.
         */
        fun wordsOf(text: String): Set<String> {
            val words = LinkedHashSet<String>()
            var start = -1
            for (i in 0..text.length) {
                val inWord = i < text.length && text[i].isLetterOrDigit()
                if (inWord && start < 0) start = i
                if (!inWord && start >= 0) {
                    words += text.substring(start, i)
                    start = -1
                }
            }
            return words
        }

        /**
         * The full-text query for one word of a request: its own term ([WordForms.term]), so «лекции» finds «лекцию» and «кот» does not
         * find «который». Null for a word with nothing to look for.
         */
        fun matchOf(word: String): String? = wordsOf(WordForms.plain(word)).map(WordForms::term).takeIf { it.isNotEmpty() }?.joinToString(" ")

        /** The query for a word still being typed: every term it begins ([SearchWords.open]); null for one too short or not all letters. */
        fun beginningOf(word: String): String? = WordForms.plain(word).takeIf { it.length >= SearchWords.BEGUN && it.all(Char::isLetter) }?.let { "$it*" }
    }
}
