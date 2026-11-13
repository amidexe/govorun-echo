package com.govorun.echo.commands

import android.content.Context
import com.govorun.echo.SecretStore
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject

object Recipes {
    fun all(context: Context): List<Recipe> = builtIn(context) + user(context)

    fun find(
        context: Context,
        id: String,
    ): Recipe? = all(context).firstOrNull { it.id == id }

    private fun builtIn(context: Context): List<Recipe> =
        context.assets.list("recipes").orEmpty().sorted().mapNotNull { name ->
            runCatching { Recipe.parse(context.assets.open("recipes/$name").bufferedReader().readText()) }.getOrNull()
        } + YandexHome.recipe(context)

    private fun user(context: Context): List<Recipe> =
        File(context.filesDir, "recipes").listFiles().orEmpty().filter { it.extension == "json" }
            .sortedBy { it.name }.mapNotNull { runCatching { Recipe.parse(it.readText()) }.getOrNull() }

    fun isUser(
        context: Context,
        id: String,
    ): Boolean = File(File(context.filesDir, "recipes"), "$id.json").isFile

    /** Checks and keeps a recipe pasted by a person. Throws with a readable reason. */
    @Synchronized
    fun addUser(
        context: Context,
        text: String,
    ): Recipe {
        val recipe = Recipe.parse(text)
        val reserved = builtIn(context).any { it.id == recipe.id } || CommandStore(context).referencesRecipe(recipe.id)
        writeNewRecipe(File(context.filesDir, "recipes"), recipe, reserved)
        return recipe
    }

    @Synchronized
    fun removeUser(
        context: Context,
        id: String,
    ) {
        File(File(context.filesDir, "recipes"), "$id.json").delete()
    }
}

internal fun writeNewRecipe(directory: File, recipe: Recipe, reserved: Boolean) {
    val conflict = "Этот id уже используется. Укажите новый id для своего рецепта."
    require(!reserved) { conflict }
    Files.createDirectories(directory.toPath())
    val path = directory.resolve("${recipe.id}.json").toPath()
    val output = try {
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    } catch (_: FileAlreadyExistsException) {
        throw IllegalArgumentException(conflict)
    }
    try {
        output.use { it.write(recipe.source.toByteArray(Charsets.UTF_8)) }
    } catch (failure: java.io.IOException) {
        Files.deleteIfExists(path)
        throw failure
    }
}

/** Commands and the saved fields of each service, in `files/commands.json`; secret fields in [com.govorun.echo.SecretStore]. */
class CommandStore(
    private val context: Context,
) {
    private val file = File(context.filesDir, "commands.json")
    private val secrets = SecretStore(context, "command_secrets", "echo-command-secrets")

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    fun commands(): List<Command> {
        val array = read().optJSONArray("commands") ?: return emptyList()
        return (0 until array.length()).mapNotNull { runCatching { Command.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    /** Deleted recipes may still have command bindings or settings from earlier versions. */
    internal fun referencesRecipe(id: String): Boolean =
        commands().any { it.recipeId == id } || read().optJSONObject("connections")?.has(id) == true ||
            secrets.hasEntries("$id.")

    fun save(command: Command) {
        val root = read()
        val current = commands()
        val ordered = if (current.any { it.id == command.id }) current.map { if (it.id == command.id) command else it } else current + command
        root.put("commands", JSONArray(ordered.map { it.toJson() }))
        file.writeText(root.toString())
    }

    fun delete(id: String) {
        val root = read()
        root.put("commands", JSONArray(commands().filter { it.id != id }.map { it.toJson() }))
        file.writeText(root.toString())
    }

    /** What a person typed for this service once: shared by all commands that use it. */
    fun connection(recipe: Recipe): Map<String, String> {
        val saved = read().optJSONObject("connections")?.optJSONObject(recipe.id)
        return recipe.fields.associate { field ->
            field.id to if (field.secret) secrets.get("${recipe.id}.${field.id}").orEmpty() else saved?.optString(field.id).orEmpty()
        }
    }

    fun saveConnection(
        recipe: Recipe,
        values: Map<String, String>,
    ) {
        val root = read()
        val all = root.optJSONObject("connections") ?: JSONObject()
        val plain = JSONObject()
        recipe.fields.forEach { field ->
            val value = values[field.id].orEmpty()
            if (field.secret) secrets.put("${recipe.id}.${field.id}", value) else plain.put(field.id, value)
        }
        all.put(recipe.id, plain)
        root.put("connections", all)
        file.writeText(root.toString())
    }
}
