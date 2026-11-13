package com.govorun.echo.commands

import org.json.JSONArray
import org.json.JSONObject

/** One thing a person types in once: an address, a token. */
data class Field(
    val id: String,
    val label: String,
    val secret: Boolean = false,
    val hint: String = "",
)

/** What a recipe can do: one request with its own fields. */
data class Action(
    val id: String,
    val name: String,
    val fields: List<Field>,
    val request: JSONObject?,
    /** Instead of a request: a broadcast to another app on this phone (Tasker and the like). */
    val intent: JSONObject? = null,
    val reply: String?,
    /** The action needs what is said after the phrase (a task, a message), so the phrase may be only a beginning. */
    val needsRest: Boolean = false,
    val android: AndroidCommand? = null,
    internal val yandexScenario: Boolean = false,
)

/** A way to talk to a service, written as data (see docs/RECIPES.md). */
data class Recipe(
    val id: String,
    val name: String,
    val fields: List<Field>,
    val actions: List<Action>,
    /** The recipe as it was written, to show and copy. */
    val source: String = "",
) {
    fun action(id: String): Action? = actions.firstOrNull { it.id == id }

    companion object {
        /** Throws [IllegalArgumentException] with a readable reason when the text is not a usable recipe. */
        fun parse(text: String): Recipe {
            require(text.length <= 65_536) { "Рецепт слишком большой" }
            // Check nesting before JSONObject reads this untrusted input recursively.
            var depth = 0
            var quoted = false
            var escaped = false
            for (char in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> require(++depth <= 32) { "Рецепт слишком глубоко вложен" }
                    '}', ']' -> require(--depth >= 0) { "Это не JSON" }
                    '\'', '/', '#', '\\' -> throw IllegalArgumentException("Это не JSON")
                }
            }
            require(!quoted && depth == 0) { "Это не JSON" }
            val json =
                try {
                    JSONObject(text)
                } catch (error: Exception) {
                    throw IllegalArgumentException("Это не JSON")
                }
            val id = json.optString("id").trim()
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,39}"))) { "Нужен id из латинских букв, цифр и дефиса" }
            val actions = json.optJSONArray("actions") ?: JSONArray()
            require(actions.length() > 0) { "Нужно хотя бы одно действие в actions" }
            require(actions.length() <= 32) { "В одном рецепте может быть до 32 действий" }
            return Recipe(
                id = id,
                name = json.optString("name").ifBlank { id },
                fields = fields(json.optJSONArray("fields")),
                actions =
                    (0 until actions.length()).map { index ->
                        val item = actions.getJSONObject(index)
                        val request = item.optJSONObject("request")
                        val intent = item.optJSONObject("intent")
                        val android = if (request == null && intent == null) AndroidCommand.fromJson(item) else null
                        require(request != null || intent != null || android != null) { "У действия ${index + 1} нет request, intent или android" }
                        require(request == null || request.optString("url").isNotBlank()) { "У действия ${index + 1} нет url" }
                        Action(
                            id = item.optString("id").ifBlank { "action${index + 1}" },
                            name = item.optString("name").ifBlank { "Действие ${index + 1}" },
                            fields = fields(item.optJSONArray("fields")),
                            request = request,
                            intent = intent,
                            reply = item.optString("reply").ifBlank { null },
                            needsRest = item.optBoolean("needsRest"),
                            android = android,
                        )
                    },
                source = text,
            )
        }

        private fun fields(array: JSONArray?): List<Field> {
            if (array == null) return emptyList()
            require(array.length() <= 32) { "В одном списке может быть до 32 полей" }
            return (0 until array.length()).map {
                val item = array.getJSONObject(it)
                Field(item.getString("id"), item.optString("label").ifBlank { item.getString("id") }, item.optBoolean("secret"), item.optString("hint"))
            }
        }
    }
}

/** A voice command: what to hear, where to send it. */
data class Command(
    val id: String,
    val name: String,
    val phrases: List<String>,
    val recipeId: String,
    val actionId: String,
    val values: Map<String, String> = emptyMap(),
    val discard: Boolean = true,
    /** True: the phrase may be only the beginning of what is said. False: the whole text must be the phrase. */
    val prefix: Boolean = true,
    val notifyResult: Boolean = true,
    val blockOnLockScreen: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("phrases", JSONArray(phrases))
            .put("recipe", recipeId)
            .put("action", actionId)
            .put("values", JSONObject(values))
            .put("discard", discard)
            .put("prefix", prefix)
            .put("notifyResult", notifyResult)
            .put("blockOnLockScreen", blockOnLockScreen)

    companion object {
        fun fromJson(json: JSONObject): Command {
            val values = json.optJSONObject("values")
            val phrases = json.optJSONArray("phrases")
            return Command(
                id = json.getString("id"),
                name = json.optString("name"),
                phrases = (0 until (phrases?.length() ?: 0)).map { phrases!!.getString(it) },
                recipeId = json.getString("recipe"),
                actionId = json.getString("action"),
                values = values?.keys()?.asSequence()?.associateWith { values.getString(it) } ?: emptyMap(),
                discard = json.optBoolean("discard", true),
                prefix = json.optBoolean("prefix", true),
                notifyResult = json.optBoolean("notifyResult", true),
                blockOnLockScreen = json.optBoolean("blockOnLockScreen", false),
            )
        }
    }
}
