package com.govorun.echo.commands

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Fills `{{name}}` in the texts of a request. */
object Template {
    private val slot = Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}")

    /** [encode] percent-encodes spoken text (not the fields a person typed) for use inside a URL. */
    fun render(
        text: String,
        vars: Map<String, String>,
        encode: Boolean = false,
    ): String =
        slot.replace(text) { match ->
            val name = match.groupValues[1]
            val value = vars[name].orEmpty()
            if (encode && !name.startsWith("field.") && !name.startsWith("action.")) URLEncoder.encode(value, "UTF-8").replace("+", "%20") else value
        }

    fun renderJson(
        node: Any?,
        vars: Map<String, String>,
    ): Any? =
        when (node) {
            is String -> render(node, vars)
            is JSONObject -> JSONObject().also { out -> node.keys().forEach { out.put(it, renderJson(node.get(it), vars)) } }
            is JSONArray -> JSONArray().also { out -> (0 until node.length()).forEach { out.put(renderJson(node.get(it), vars)) } }
            else -> node
        }

    /** Follows `a.b.0.c` through a JSON answer and returns the string found there, or null. */
    fun pick(
        answer: String,
        path: String,
    ): String? {
        var node: Any? =
            try {
                if (answer.trimStart().startsWith("[")) JSONArray(answer) else JSONObject(answer)
            } catch (error: Exception) {
                return null
            }
        for (step in path.split('.')) {
            node =
                when (node) {
                    is JSONObject -> node.opt(step)
                    is JSONArray -> step.toIntOrNull()?.let { node.opt(it) }
                    else -> null
                }
            if (node == null || node == JSONObject.NULL) return null
        }
        return node.toString().takeIf { it.isNotBlank() }
    }
}
