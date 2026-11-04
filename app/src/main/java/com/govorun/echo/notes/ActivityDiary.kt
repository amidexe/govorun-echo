package com.govorun.echo.notes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What was done, not what is kept: «Итоги» count days with notes and minutes said from this small diary, so deleting a note
 * never takes back a day or a streak (as Duolingo, Apple Fitness and Day One count). It holds dates and numbers only — no text,
 * no sound — in Echo's private folder, and goes with the Android backup and a phone moved to a new one, as the notes' snapshot does.
 *
 * Each note is counted once, by its id, with the time it had; a note said into again later adds only what grew, on the day it
 * grew. Joined notes are made of notes already counted and are skipped; a record of a few seconds is a slip, not a day.
 */
object ActivityDiary {
    const val FILE = "activity.json"

    /** A record shorter than this keeps no streak: a tap by mistake is not a day with a note. */
    const val MIN_MS = 5_000L

    data class Day(
        val ms: Long,
        val notes: Int,
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun days(context: Context): Map<LocalDate, Day> {
        val json = read(file(context))
        val days = json.optJSONObject("days") ?: return emptyMap()
        return days.keys().asSequence().mapNotNull { key ->
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to days.getJSONObject(key).let { d -> Day(d.optLong("ms"), d.optInt("notes")) } }
        }.toMap()
    }

    /** Counts what is new in [notes]: notes not seen before, and what notes seen before have grown by. */
    @Synchronized
    fun update(
        context: Context,
        notes: List<NoteStore.Entry>,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val f = file(context)
        val json = read(f)
        val days = json.optJSONObject("days") ?: JSONObject()
        val seen = json.optJSONObject("seen") ?: JSONObject()
        var changed = false
        fun add(
            at: Long,
            ms: Long,
            notes: Int,
        ) {
            val key = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
            val day = days.optJSONObject(key) ?: JSONObject()
            days.put(key, day.put("ms", day.optLong("ms") + ms).put("notes", day.optInt("notes") + notes))
            changed = true
        }
        for (note in notes) {
            // Said is said once the record stops: a note whose text never came (Govorun away, an error) was a day with a note too.
            if (note.state == NoteStore.STATE_RECORDING || note.mode == "merged" || note.mode == NoteImport.MODE) continue
            val counts = note.durationMs >= MIN_MS || (note.mode == NoteText.MODE && note.text.isNotBlank())
            if (!counts) continue
            if (!seen.has(note.id)) {
                add(note.startedAtMs, note.durationMs, 1)
                seen.put(note.id, note.durationMs)
            } else {
                val grew = note.durationMs - seen.optLong(note.id)
                if (grew > 0) {
                    add(note.modifiedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis(), grew, 0)
                    seen.put(note.id, note.durationMs)
                }
            }
        }
        // Once, on the diary's first day: the notes already in the trash were said too, so a cleanup just before it took nothing back.
        if (!json.optBoolean("trashSeen")) {
            NoteTrash.root(context).listFiles { d -> d.isDirectory }.orEmpty().forEach { dir ->
                val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return@forEach
                val mode = meta.optString("mode")
                val ms = meta.optLong("durationMs")
                val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
                val counts = ms >= MIN_MS || (mode == NoteText.MODE && text.isNotBlank())
                if (meta.optString("state") == NoteStore.STATE_RECORDING || mode == "merged" || mode == NoteImport.MODE || !counts || seen.has(dir.name)) return@forEach
                add(meta.optLong("startedAtMs"), ms, 1)
                seen.put(dir.name, ms)
            }
            json.put("trashSeen", true)
            changed = true
        }
        if (changed || !f.exists()) write(f, json.put("days", days).put("seen", seen))
    }

    /** «Сбросить итоги»: the diary starts again from today's notes on. */
    @Synchronized
    fun reset(
        context: Context,
        notes: List<NoteStore.Entry>,
    ) {
        val seen = JSONObject()
        notes.forEach { seen.put(it.id, it.durationMs) }
        write(file(context), JSONObject().put("days", JSONObject()).put("seen", seen))
    }

    private fun read(f: File): JSONObject = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())

    private fun write(
        f: File,
        json: JSONObject,
    ) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
    }
}
