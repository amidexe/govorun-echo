package com.govorun.echo

import org.json.JSONObject

internal const val CAPTURE_STOP_CAP = "cap"

/** The action the person chose, independent of the note/long recording length. */
enum class CapturePurpose(val id: String) {
    NOTE("note"),
    QUICK("quick"),
    COMMAND("command");

    companion object {
        const val FIELD = "capturePurpose"

        fun of(id: String?): CapturePurpose = entries.firstOrNull { it.id == id } ?: NOTE
        fun read(meta: JSONObject): CapturePurpose = of(meta.optString(FIELD))
    }
}
