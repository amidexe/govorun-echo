package com.govorun.echo.commands

import java.util.UUID

/** A template creates an independent command. Names and trigger phrases belong to the person. */
internal enum class CommandTemplate(val recipe: String, val action: String, val phrase: String) {
    Calendar("android-calendar", "event", "календарь"),
    Alarm("android-clock", "alarm", "будильник"),
    Timer("android-clock", "timer", "таймер"),
    Yandex(YandexHome.RECIPE, YandexHome.ACTION, "");

    fun create(name: String): Command = Command(UUID.randomUUID().toString(), name, listOfNotNull(phrase.ifBlank { null }), recipe, action,
        values = emptyMap(), discard = false, prefix = this != Yandex)

    companion object {
        fun of(command: Command): CommandTemplate? = if (command.recipeId == "android-calendar" && command.actionId == "reminder") Calendar else entries.firstOrNull {
            command.recipeId == it.recipe && command.actionId == it.action
        }
    }
}
