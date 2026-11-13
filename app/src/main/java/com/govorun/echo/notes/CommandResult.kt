package com.govorun.echo.notes

@androidx.compose.runtime.Immutable
data class CommandResult(
    val name: String,
    val ok: Boolean,
    val label: String,
    val reply: String,
    val kind: String = "",
    val phase: String = "",
    val title: String = "",
    val detail: String = "",
    val reminderMinutes: Int = -1
) {
    companion object {
        fun read(json: org.json.JSONObject, expired: String, unknown: String): CommandResult {
            var phase = json.optString("phase")
            var label = json.optString("label")
            val ended = json.optLong("expiresAtMs") > 0 && System.currentTimeMillis() >= json.optLong("expiresAtMs")
            if (ended && phase in setOf("processing", "dispatching")) {
                val dispatched = phase == "dispatching"
                phase = if (dispatched) "unknown" else "failed"
                label = if (dispatched) unknown else expired
            }
            return CommandResult(json.optString("name"), json.optBoolean("ok"), label, json.optString("reply"),
                json.optString("kind"), phase, json.optJSONObject("parameters")?.optString("title").orEmpty(),
                json.optString("detail"), json.optJSONObject("parameters")?.optInt("reminderMinutes", -1) ?: -1)
        }
    }
}
