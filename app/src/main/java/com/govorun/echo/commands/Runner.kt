package com.govorun.echo.commands

import java.io.IOException
import java.net.HttpURLConnection
import com.govorun.echo.NetworkPolicy
import java.time.OffsetDateTime
import org.json.JSONObject

/** Sends the request of a command. No libraries: one plain HTTP call with short timeouts and one retry. */
object Runner {
    data class Result(
        val ok: Boolean,
        val code: Int,
        val reply: String?,
        val error: String?,
    ) {
        /** A short line for the note. */
        fun label(): String = if (ok) "отправлено" else error ?: "ошибка $code"
    }

    /** What is about to be sent, as text: used by the phrase checker and the test button. */
    class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    ) {
        fun describe(): String = buildString {
            append(method).append(' ').append(url)
            headers.forEach { (name, value) -> append('\n').append(name).append(": ").append(if (name.equals("Authorization", true)) "•••" else value) }
            body?.let { append('\n').append(it) }
        }
    }

    fun variables(
        command: Command,
        match: Matcher.Match,
        connection: Map<String, String>,
        noteId: String,
    ): Map<String, String> =
        buildMap {
            put("text", match.trigger + (if (match.rest.isNotEmpty()) " " + match.rest else ""))
            put("rest", match.rest)
            put("trigger", match.trigger)
            put("command", command.name)
            put("time", OffsetDateTime.now().toString())
            put("note_id", noteId)
            // Typed addresses often end with a slash or a space; both would break "{{field.url}}/api/…".
            connection.forEach { (key, value) -> put("field.$key", clean(key, value)) }
            command.values.forEach { (key, value) -> put("action.$key", clean(key, value)) }
        }

    private fun clean(
        key: String,
        value: String,
    ): String = value.trim().let { if (key == "url") it.trimEnd('/') else it }

    /** A broadcast to another app: its action, its package (required, so only that app hears the text) and the extras. */
    class Broadcast(
        val action: String,
        val packageName: String,
        val extras: Map<String, String>,
    ) {
        fun describe(): String = "Intent $action → $packageName\n" + extras.entries.joinToString("\n") { "${it.key} = ${it.value}" }
    }

    fun buildBroadcast(
        action: Action,
        vars: Map<String, String>,
    ): Broadcast? {
        val spec = action.intent ?: return null
        val extras = mutableMapOf<String, String>()
        spec.optJSONObject("extras")?.let { json -> json.keys().forEach { extras[it] = Template.render(json.getString(it), vars) } }
        return Broadcast(
            Template.render(spec.optString("action"), vars).ifBlank { DEFAULT_INTENT_ACTION },
            Template.render(spec.optString("package"), vars).trim(),
            extras,
        )
    }

    fun send(
        context: android.content.Context,
        broadcast: Broadcast,
    ): Result {
        if (broadcast.packageName.isBlank()) return Result(false, 0, null, "не указан пакет")
        val intent = android.content.Intent(broadcast.action).setPackage(broadcast.packageName)
        broadcast.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return runCatching { context.sendBroadcast(intent) }.fold({ Result(true, 0, null, null) }, { Result(false, 0, null, "не отправлено") })
    }

    fun build(
        action: Action,
        vars: Map<String, String>,
    ): Request {
        val request = action.request ?: throw IllegalArgumentException("нет request")
        val method = request.optString("method", "POST").uppercase()
        val headers = mutableMapOf<String, String>()
        request.optJSONObject("headers")?.let { json -> json.keys().forEach { name -> Template.render(json.getString(name), vars).takeIf { it.isNotBlank() }?.let { headers[name] = it } } }
        val body = if (method == "GET" || !request.has("body")) null else Template.renderJson(request.get("body"), vars).toString()
        if (body != null && headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/json; charset=utf-8"
        return Request(method, Template.render(request.getString("url"), vars, encode = true), headers, body)
    }

    /** Sends what the action describes: a request to an address or a broadcast to an app. */
    fun sendCommand(
        context: android.content.Context,
        action: Action,
        vars: Map<String, String>,
    ): Result {
        val broadcast = buildBroadcast(action, vars)
        return if (broadcast != null) send(context, broadcast) else send(build(action, vars), action.reply)
    }

    fun send(
        request: Request,
        replyPath: String?,
    ): Result {
        var last = Result(false, 0, null, "нет связи")
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_PAUSE_MS)
            try {
                return once(request, replyPath)
            } catch (error: com.govorun.echo.NetworkGate.Refused) {
                return Result(false, 0, null, when (error.reason) {
                    com.govorun.echo.NetworkGate.ROAMING -> "Офлайн в роуминге"
                    com.govorun.echo.NetworkGate.OFFLINE -> "Включён «Офлайн»"
                    else -> "нет связи"
                })
            } catch (error: IOException) {
                last = Result(false, 0, null, "нет связи")
            } catch (error: IllegalArgumentException) {
                return Result(false, 0, null, "неверный адрес")
            }
        }
        return last
    }

    private fun once(
        request: Request,
        replyPath: String?,
    ): Result = NetworkPolicy.request(request.url) { connection ->
        run {
            connection.requestMethod = request.method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val answer = stream?.use { it.readNBytes(MAX_ANSWER).toString(Charsets.UTF_8) }.orEmpty()
            val reply = if (code in 200..299 && replyPath != null) Template.pick(answer, replyPath) else null
            Result(code in 200..299, code, reply?.take(MAX_REPLY), if (code in 200..299) null else "ошибка $code")
        }
    }

    const val DEFAULT_INTENT_ACTION = "com.govorun.echo.action.COMMAND"
    private const val TIMEOUT_MS = 8_000
    private const val RETRY_PAUSE_MS = 3_000L
    private const val MAX_ANSWER = 64 * 1024
    private const val MAX_REPLY = 160
}
