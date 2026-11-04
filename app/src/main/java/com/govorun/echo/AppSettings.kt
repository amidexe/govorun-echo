package com.govorun.echo

import android.content.Context
import android.content.SharedPreferences

/** The few user choices the app has. */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_HOTKEY = "hotkey_enabled"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC = "dynamic_colors"
    private const val KEY_RETENTION = "retention_days"
    private const val KEY_COLLAPSED_DAYS = "collapsed_days"
    private const val KEY_PLAY_NEXT = "play_next"
    private const val KEY_STAMPS = "stamps_in_text"
    private const val KEY_MARK_APPENDED = "mark_appended"
    private const val KEY_EXPORT_KINDS = "export_kinds"
    private const val KEY_TAGS = "tags_made"
    private const val KEY_LAST_TAG = "last_tag"
    private const val KEY_TAG_STYLES = "tag_styles"
    private const val KEY_TAG_ORDER = "tag_order"
    private const val KEY_TAG_USED = "tag_used"
    private const val KEY_ONBOARDING = "onboarding_done"
    private const val KEY_ADVANCED = "advanced_settings"
    private const val KEY_INSTALL_INFO = "install_info_json"
    private const val KEY_PLAY_SPEED = "play_speed"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_PLAY_SCREEN = "play_screen"
    private const val KEY_RAISE_TO_LISTEN = "raise_to_listen"
    private const val KEY_MICROPHONE = "microphone_input"
    private const val KEY_SILENCE_STOP = "silence_stop_minutes"
    private const val KEY_VOLUME_KEY_HOLDS = "volume_key_holds"
    private const val KEY_RECORDING_TAPS = "recording_taps"
    private const val KEY_SAVED_NOTICE = "saved_notice"
    private const val KEY_PINS_OPEN = "pins_open"
    private const val KEY_APP_LOCK = "app_lock"
    private const val KEY_ACTION_SOUNDS = "action_sounds"
    private const val KEY_DROP_EMPTY = "drop_empty"
    private const val KEY_EMPTY_SWEPT = "empty_swept_2"
    // A new key: the old «brief_view» was a mode turned on and off by ✦; the look is now on for everyone until turned off.
    private const val KEY_BRIEF_OPEN = "brief_open"
    private const val KEY_FAIR_STYLE = "fair_style"
    private const val KEY_AUTO_TITLES_CLEARED = "auto_titles_cleared"
    private const val KEY_MARKDOWN_COPY = "markdown_copy"
    private const val KEY_SEARCH_ORDER = "search_order"
    private const val KEY_SMART_FUNCTIONS = "smart_functions"


    /**
     * How a search lays out what it finds: one list, the best answer first ("ranked", the default); the best three on top and the
     * rest by days ("best"); or all words needed and by days, newest first ("days", as before 0.1.62).
     */
    const val SEARCH_RANKED = "ranked"
    const val SEARCH_BEST = "best"
    const val SEARCH_DAYS = "days"

    fun searchOrder(context: Context): String = prefs(context).getString(KEY_SEARCH_ORDER, SEARCH_RANKED).takeIf { it == SEARCH_BEST || it == SEARCH_DAYS } ?: SEARCH_RANKED

    fun setSearchOrder(context: Context, value: String) = prefs(context).edit().putString(KEY_SEARCH_ORDER, value).apply()

    /** Playback speed, 0.75x to 2x. */
    fun playSpeed(context: Context): Float = prefs(context).getFloat(KEY_PLAY_SPEED, 1f).coerceIn(0.5f, 3f)

    fun setPlaySpeed(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_PLAY_SPEED, value).apply()

    /** Whether playback jumps over the pauses in a note. */
    fun skipSilence(context: Context): Boolean = prefs(context).getBoolean(KEY_SKIP_SILENCE, false)

    fun setSkipSilence(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SKIP_SILENCE, on).apply()

    /** The screen while a note plays: "dim" (stays on, dims when untouched, finally lets go), "always" or "system". */
    const val SCREEN_DIM = "dim"
    const val SCREEN_ALWAYS = "always"
    const val SCREEN_SYSTEM = "system"

    fun playScreen(context: Context): String = prefs(context).getString(KEY_PLAY_SCREEN, SCREEN_DIM).takeIf { it == SCREEN_ALWAYS || it == SCREEN_SYSTEM } ?: SCREEN_DIM

    fun setPlayScreen(context: Context, value: String) = prefs(context).edit().putString(KEY_PLAY_SCREEN, value).apply()

    /** Opt in: a finger near controls must not turn the screen off by default. */
    fun raiseToListen(context: Context): Boolean = prefs(context).getBoolean(KEY_RAISE_TO_LISTEN, false)

    fun setRaiseToListen(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_RAISE_TO_LISTEN, value).apply()

    const val MICROPHONE_PHONE = "phone"
    const val MICROPHONE_HEADSET = "headset"

    /** Read once at recording start; changing this never moves a running recording. */
    fun microphone(context: Context): String =
        prefs(context).getString(KEY_MICROPHONE, MICROPHONE_PHONE).takeIf { it == MICROPHONE_HEADSET } ?: MICROPHONE_PHONE

    fun setMicrophone(context: Context, value: String) =
        prefs(context).edit().putString(KEY_MICROPHONE, if (value == MICROPHONE_HEADSET) value else MICROPHONE_PHONE).apply()

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2


    fun advanced(context: Context): Boolean = prefs(context).getBoolean(KEY_ADVANCED, false)

    fun setAdvanced(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ADVANCED, value).apply()


    /** Hold volume-down on the lock screen to record. Short presses always stay volume. */
    fun hotkeyEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HOTKEY, true)

    fun setHotkeyEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HOTKEY, value).apply()

    fun themeMode(context: Context): Int = prefs(context).getInt(KEY_THEME, THEME_SYSTEM)

    fun setThemeMode(context: Context, value: Int) = prefs(context).edit().putInt(KEY_THEME, value).apply()

    fun dynamicColors(context: Context): Boolean = prefs(context).getBoolean(KEY_DYNAMIC, false)

    fun setDynamicColors(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_DYNAMIC, value).apply()

    /** Notes older than this many days are deleted when the app opens; 0 keeps everything. */
    fun autoArchive(context: Context): Boolean = prefs(context).getBoolean("auto_archive", false)
    fun setAutoArchive(context: Context, on: Boolean) { prefs(context).edit().putBoolean("auto_archive", on).apply() }

    fun retentionDays(context: Context): Int = prefs(context).getInt(KEY_RETENTION, 0)

    fun setRetentionDays(context: Context, value: Int) = prefs(context).edit().putInt(KEY_RETENTION, value).apply()

    /** Keep the recording as compressed audio next to its text so it can be played back. */
    /** Tags made with "+" before any note has them; a tag put on a note by hand lives only as long as a note carries it. */
    /** The days the person folded in the list (ISO dates): they stay folded until opened again. */
    fun collapsedDays(context: Context): Set<String> = prefs(context).getStringSet(KEY_COLLAPSED_DAYS, emptySet()).orEmpty()

    /** Keeps the folded days, the newest few hundred: an old folded day is as good as forgotten. */
    fun setCollapsedDays(context: Context, days: Set<String>) =
        prefs(context).edit().putStringSet(KEY_COLLAPSED_DAYS, days.sortedDescending().take(400).toSet()).apply()

    /** Only the named tag is remembered; search, selection and other filters stay temporary. */
    fun lastTag(context: Context): String? = prefs(context).getString(KEY_LAST_TAG, null)
    fun setLastTag(context: Context, tag: String?) = prefs(context).edit().putString(KEY_LAST_TAG, tag).apply()

    fun tags(context: Context): Set<String> = prefs(context).getStringSet(KEY_TAGS, emptySet()).orEmpty()

    fun addTag(context: Context, tag: String) = prefs(context).edit().putStringSet(KEY_TAGS, tags(context) + tag).apply()

    fun removeTag(context: Context, tag: String) = prefs(context).edit().putStringSet(KEY_TAGS, tags(context) - tag).apply()

    fun tagAliases(context: Context): Map<String, String> = TagAliases.read(prefs(context).getString("tag_short_names", null))
    fun setTagAlias(context: Context, tag: String, short: String) {
        val values = tagAliases(context).toMutableMap()
        if (short.isBlank()) values.remove(tag) else values[tag] = short.trim()
        prefs(context).edit().putString("tag_short_names", TagAliases.write(values)).apply()
    }

    /** Colour index and "pinned" per tag, kept as "name=colour,pinned" lines; a tag never styled has no line. */
    fun tagStyles(context: Context): Map<String, Pair<Int?, Boolean>> =
        prefs(context).getStringSet(KEY_TAG_STYLES, emptySet()).orEmpty().mapNotNull { line ->
            val split = line.lastIndexOf('=')
            if (split <= 0) return@mapNotNull null
            val (color, pinned) = line.substring(split + 1).split(',').let { (it.getOrNull(0)?.toIntOrNull()) to (it.getOrNull(1) == "1") }
            line.substring(0, split) to (color to pinned)
        }.toMap()

    fun setTagStyle(context: Context, tag: String, color: Int?, pinned: Boolean) {
        val kept = prefs(context).getStringSet(KEY_TAG_STYLES, emptySet()).orEmpty().filterNot { it.startsWith("$tag=") }
        val line = "$tag=${color ?: ""},${if (pinned) 1 else 0}"
        prefs(context).edit().putStringSet(KEY_TAG_STYLES, (kept + line).toSet()).apply()
    }

    fun removeTagStyle(context: Context, tag: String) =
        prefs(context).edit().putStringSet(KEY_TAG_STYLES, prefs(context).getStringSet(KEY_TAG_STYLES, emptySet()).orEmpty().filterNot { it.startsWith("$tag=") }.toSet()).apply()

    /** The order the owner dragged the tags into; a tag not in it goes after the others. */
    fun tagOrder(context: Context): List<String> = prefs(context).getString(KEY_TAG_ORDER, "").orEmpty().split('\u0001').filter { it.isNotEmpty() }

    fun setTagOrder(context: Context, order: List<String>) = prefs(context).edit().putString(KEY_TAG_ORDER, order.joinToString("\u0001")).apply()

    /** A tag's new name among the made tags, colours and pins, in the row's order and its last use; a name already taken makes the two one. */
    fun renameTag(context: Context, from: String, to: String) {
        val made = tags(context)
        prefs(context).edit()
            .putString("tag_short_names", TagAliases.write(TagAliases.renamed(tagAliases(context), from, to)))
            .putStringSet(KEY_TAGS, if (from in made) made - from + to else made)
            .putStringSet(KEY_TAG_STYLES, renamedStyles(prefs(context).getStringSet(KEY_TAG_STYLES, emptySet()).orEmpty(), from, to))
            .putString(KEY_TAG_ORDER, tagOrder(context).map { if (it == from) to else it }.distinct().joinToString("\u0001"))
            .putString(KEY_TAG_USED, tagUseText(renamedUse(tagUse(context), from, to)))
            .apply()
    }

    /** The "name=colour,pinned" lines after a rename: a name already taken keeps its own colour, and stays pinned if either was. */
    fun renamedStyles(lines: Set<String>, from: String, to: String): Set<String> {
        fun style(tag: String) = lines.firstOrNull { it.startsWith("$tag=") }?.substringAfter("$tag=")?.split(',')?.let { it.getOrNull(0)?.toIntOrNull() to (it.getOrNull(1) == "1") }
        val old = style(from) ?: return lines
        val own = style(to)
        val color = own?.first ?: old.first
        val pinned = own?.second == true || old.second
        return lines.filterNot { it.startsWith("$from=") || it.startsWith("$to=") }.toSet() + "$to=${color ?: ""},${if (pinned) 1 else 0}"
    }

    /** When each tag was last put on a note or made, so the tag sheets can show the recently used ones first. */
    fun tagUse(context: Context): Map<String, Long> = tagUseOf(prefs(context).getString(KEY_TAG_USED, null))

    fun setTagUse(context: Context, use: Map<String, Long>) = prefs(context).edit().putString(KEY_TAG_USED, tagUseText(use)).apply()

    /** The stamps kept as one JSON object, so a tag's name may hold any character. */
    fun tagUseOf(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.optLong(it) }.filterValues { it > 0 }
    }

    fun tagUseText(use: Map<String, Long>): String = org.json.JSONObject().apply { use.forEach { (tag, at) -> put(tag, at) } }.toString()

    /** The stamps after a rename: the new name keeps the later of the two. */
    fun renamedUse(use: Map<String, Long>, from: String, to: String): Map<String, Long> {
        val at = use[from] ?: return use
        return use - from + (to to maxOf(at, use[to] ?: 0L))
    }

    /** The tag settings as they go into the Google backup: the made tags, their colours, their order and when they were last used. */
    fun tagBackup(context: Context): org.json.JSONObject =
        org.json.JSONObject()
            .put("made", org.json.JSONArray(tags(context).toList()))
            .put("styles", org.json.JSONArray(prefs(context).getStringSet(KEY_TAG_STYLES, emptySet()).orEmpty().toList()))
            .put("order", org.json.JSONArray(tagOrder(context)))
            .put("used", org.json.JSONObject(tagUseText(tagUse(context))))

    fun restoreTags(context: Context, json: org.json.JSONObject) {
        fun list(name: String) = json.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        prefs(context).edit()
            .putStringSet(KEY_TAGS, list("made").toSet())
            .putStringSet(KEY_TAG_STYLES, list("styles").toSet())
            .putString(KEY_TAG_ORDER, list("order").joinToString("\u0001"))
            .putString(KEY_TAG_USED, tagUseText(tagUseOf(json.optJSONObject("used")?.toString())))
            .apply()
    }

    /** Whether copied, shared and exported text of several notes (or a joined note) carries the time of each piece. On by default. */
    fun stampsInText(context: Context): Boolean = prefs(context).getBoolean(KEY_STAMPS, true)

    fun setStampsInText(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_STAMPS, value).apply()

    /** What the owner sent last time from «Отправить файл» (names of the kinds); the sound alone at first. */
    fun exportKinds(context: Context): Set<String> = prefs(context).getStringSet(KEY_EXPORT_KINDS, null)?.toSet() ?: setOf("Audio")

    fun setExportKinds(context: Context, kinds: Set<String>) = prefs(context).edit().putStringSet(KEY_EXPORT_KINDS, kinds).apply()

    fun markAppended(context: Context): Boolean = prefs(context).getBoolean(KEY_MARK_APPENDED, true)

    fun setMarkAppended(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_MARK_APPENDED, value).apply()

    internal fun textOptions(context: Context): TextOptions = TextOptions(stampsInText(context), markAppended(context))

    fun playNext(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAY_NEXT, false)

    fun setPlayNext(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_PLAY_NEXT, value).apply()



    /** The choices for «Остановить, если долго тихо», in minutes without speech; 0 is never. */
    val SILENCE_STOP_MINUTES = listOf(0, 30, 60, 120)

    /** A recording Govorun has heard no speech in for this long ends by itself; an hour unless the person chose otherwise. */
    fun silenceStopMinutes(context: Context): Int = prefs(context).getInt(KEY_SILENCE_STOP, 60).coerceAtLeast(0)

    fun silenceStopMs(context: Context): Long = silenceStopMinutes(context) * 60_000L

    fun setSilenceStopMinutes(context: Context, value: Int) = prefs(context).edit().putInt(KEY_SILENCE_STOP, value).apply()

    /** How many notes were recorded by holding Govorun's volume key; the card stops explaining itself after the first few. */
    fun volumeKeyHolds(context: Context): Int = prefs(context).getInt(KEY_VOLUME_KEY_HOLDS, 0)

    fun countVolumeKeyHold(context: Context) = prefs(context).edit().putInt(KEY_VOLUME_KEY_HOLDS, volumeKeyHolds(context) + 1).apply()

    /** A tap when a recording starts, two when it is saved, one a minute before it stops for silence. */
    fun recordingTaps(context: Context): Boolean = prefs(context).getBoolean(KEY_RECORDING_TAPS, true)

    fun setRecordingTaps(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_RECORDING_TAPS, value).apply()

    /** «Вход по отпечатку или коду» ([com.govorun.echo.ui.AppLock]); off unless the person turns it on. */
    fun appLock(context: Context): Boolean = prefs(context).getBoolean(KEY_APP_LOCK, false)

    fun setAppLock(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** «Тихие звуки» ([com.govorun.echo.ui.ActionSounds]) on deleting and archiving; off until the person turns them on. */
    fun actionSounds(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTION_SOUNDS, true)

    fun setActionSounds(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ACTION_SOUNDS, value).apply()

    /** «Убирать пустые записи» ([com.govorun.echo.notes.EmptyNotes]); on until the person turns it off. */
    fun dropEmpty(context: Context): Boolean = prefs(context).getBoolean(KEY_DROP_EMPTY, false)

    fun setDropEmpty(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_DROP_EMPTY, value).apply()

    /** The empty records made before the switch existed were cleared once. */
    fun emptySwept(context: Context): Boolean = prefs(context).getBoolean(KEY_EMPTY_SWEPT, false)

    fun setEmptySwept(context: Context) = prefs(context).edit().putBoolean(KEY_EMPTY_SWEPT, true).apply()

    /** The pinned group at the top of the list is open; folded, it stays folded as the days do. */
    fun pinsOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_PINS_OPEN, true)

    fun setPinsOpen(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_PINS_OPEN, value).apply()

    /** «Заметка готова» in the shade once a note's text has come; off unless the person turns it on. */
    fun savedNotice(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVED_NOTICE, false)

    fun setSavedNotice(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SAVED_NOTICE, value).apply()

    /** The list in one line a note (its name, tags and time) instead of a card with lines of text. */
    /**
     * A card shows what its note is about («Кратко») instead of its first lines, which from a recorder rarely say what it was; on by
     * default with the smart functions, off in Settings → «Умные».
     */


    /** The Govorun style last chosen for «Улучшить через Говорун», offered first next time; null before the first. */
    /** The style «Улучшить» picks first: Govorun's value; null (or the former [FAIR_ASK]) for light cleanup. */
    fun fairStyle(context: Context): String? = prefs(context).getString(KEY_FAIR_STYLE, null)

    /** The style to run with without asking: the default, or null (light cleanup) when it is «ask» or not set. */
    fun fairStyleToRun(context: Context): String? = fairStyle(context)?.takeIf { it != FAIR_ASK }

    /** Manual smart actions are available by default; an explicit Echo opt-out is preserved. */
    fun smartFunctions(context: Context): Boolean = prefs(context).getBoolean(KEY_SMART_FUNCTIONS, true)

    fun setSmartFunctions(context: Context, value: Boolean): Boolean = prefs(context).edit().putBoolean(KEY_SMART_FUNCTIONS, value).commit()

    internal fun observeSmartFunctions(context: Context, changed: (Boolean) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_SMART_FUNCTIONS) changed(smartFunctions(context))
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        changed(smartFunctions(context))
        return listener
    }

    /** Automatic summaries are a second, explicit choice; enabling smart tools does not enable automation. */
    fun itogiAuto(context: Context): Boolean = prefs(context).getBoolean("itogi_auto", false)

    /** The shortest and longest note Govorun last said it makes итоги of, 0 for «not said yet». */
    fun itogiBounds(context: Context): Pair<Int, Int> = prefs(context).let { it.getInt("itogi_min", 0) to it.getInt("itogi_max", 0) }

    fun setItogiBounds(context: Context, min: Int, max: Int) = prefs(context).edit().putInt("itogi_min", min).putInt("itogi_max", max).apply()

    fun setItogiAuto(context: Context, value: Boolean) = prefs(context).edit().putBoolean("itogi_auto", value).remove("fair_economy").apply()

    const val FAIR_ASK = "ask"

    fun setFairStyle(context: Context, value: String) = prefs(context).edit().putString(KEY_FAIR_STYLE, value).apply()

    /** «Кратко» inside a note shown open (its points) or folded to what the note is about; the same for every note. */
    fun briefOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEF_OPEN, false)

    fun setBriefOpen(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEF_OPEN, value).apply()


    /** The automatic names 0.1.54 wrote into old notes were taken back ([com.govorun.echo.notes.AutoTitles]). */
    fun autoTitlesCleared(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_TITLES_CLEARED, false)

    fun setAutoTitlesCleared(context: Context) = prefs(context).edit().putBoolean(KEY_AUTO_TITLES_CLEARED, true).apply()

    /** Whether the copy folder also gets each note as a Markdown file for Obsidian (an experimental setting); off by default. */
    fun markdownCopy(context: Context): Boolean = prefs(context).getBoolean(KEY_MARKDOWN_COPY, false)

    fun setMarkdownCopy(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_MARKDOWN_COPY, value).apply()



    fun onboardingDone(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** Last install-page data fetched from the internet, kept for offline use. */
    fun installInfoJson(context: Context): String? = prefs(context).getString(KEY_INSTALL_INFO, null)

    fun setInstallInfoJson(context: Context, value: String) = prefs(context).edit().putString(KEY_INSTALL_INFO, value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** What the owner chose to put into the text that leaves the app. */
internal data class TextOptions(
    val stamps: Boolean = true,
    val markAppended: Boolean = true,
)
