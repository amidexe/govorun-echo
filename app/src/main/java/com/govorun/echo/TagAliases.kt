package com.govorun.echo

import org.json.JSONObject

/** Display-only short names. Full tag names remain stable keys on every note. */
internal object TagAliases {
    fun read(text: String?): Map<String, String> = runCatching {
        val json = JSONObject(text ?: "{}"); json.keys().asSequence().mapNotNull { key ->
            json.optString(key).trim().takeIf { it.isNotEmpty() }?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())
    fun write(values: Map<String, String>): String = JSONObject().apply { values.forEach { (name, short) -> if (short.isNotBlank()) put(name, short.trim()) } }.toString()
    fun renamed(values: Map<String, String>, from: String, to: String): Map<String, String> =
        if (from == to || from !in values) values else (values - from).toMutableMap().apply { if (to !in this) put(to, values.getValue(from)) }
}
