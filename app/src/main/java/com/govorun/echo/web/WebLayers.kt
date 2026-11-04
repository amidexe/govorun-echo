package com.govorun.echo.web

import com.govorun.echo.notes.NoteStore
import org.json.JSONObject

/** Saved results only, never a new smart request. Keep legacy summaries readable as well as today's body. */
internal object WebLayers {
    fun of(n: NoteStore.Entry, names: Map<String, String>): List<JSONObject> {
        fun layer(key: String, name: String, title: String, about: String, text: String, fresh: Boolean): JSONObject =
            JSONObject().put("key", key).put("name", name).put("title", title.take(WebExport.META_CAP)).put("about", about.take(WebExport.META_CAP))
                .put("text", text.take(WebExport.TEXT_CAP)).put("fresh", fresh)
                .put("cut", text.length > WebExport.TEXT_CAP || about.length > WebExport.META_CAP || title.length > WebExport.META_CAP)
        val summary = n.itogi?.let {
            layer("brief", names["brief"] ?: "Кратко", it.nazvanie, it.oChem, it.content, it.fresh)
        } ?: n.brief?.let {
            val points = it.points.joinToString("\n") { p -> "- " + listOf(p.at, p.text).filter(String::isNotBlank).joinToString(" ") }
            val todo = it.todo.takeIf { t -> t.isNotEmpty() }?.joinToString("\n", "Задачи\n") { t -> "- $t" }.orEmpty()
            layer("brief", names["brief"] ?: "Кратко", it.name, it.about, listOf(points, todo).filter(String::isNotBlank).joinToString("\n\n"), it.fresh)
        }
        val edited = n.fair?.takeIf { n.mode != com.govorun.echo.notes.NoteText.MODE }?.let {
            layer("fair", names["fair"] ?: "Текст", "", "", it.text, it.fresh)
        }
        return listOfNotNull(edited, summary)
    }
}
