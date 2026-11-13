package com.govorun.echo.commands

import android.app.KeyguardManager
import android.content.Context
import com.govorun.echo.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import com.govorun.echo.NetworkPolicy
import java.time.OffsetDateTime
import org.json.JSONObject

internal class CommandBlockedByScreenLock : RuntimeException()

internal fun checkCommandScreenLock(blockOnLockScreen: Boolean, isLocked: () -> Boolean) {
    if (blockOnLockScreen && isLocked()) throw CommandBlockedByScreenLock()
}

/** Sends a command to its configured destination with bounded timeouts. */
object Runner {
    data class Result(
        val ok: Boolean,
        val code: Int,
        val reply: String?,
        val error: String?,
        val deferred: Boolean = false,
        val uncertain: Boolean = false,
    ) {
        /** A short line for the note. */
        fun label(): String = if (ok) "отправлено" else error ?: "ошибка $code"
    }

    /** What is about to be sent. The UI only shows the destination and method. */
    class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
        private val origin: URI,
    ) {
        val cleartext: Boolean get() = origin.scheme == "http"
        fun describe(): String = "$method $origin"
    }

    internal data class Destination(val label: String, val cleartext: Boolean = false)

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
            putAll(configuredVariables(connection, command.values))
        }

    internal fun configuredVariables(connection: Map<String, String>, values: Map<String, String>): Map<String, String> =
        buildMap {
            connection.forEach { (key, value) -> put("field.$key", clean(key, value)) }
            values.forEach { (key, value) -> put("action.$key", clean(key, value)) }
        }

    private fun isSetting(name: String) = name.startsWith("field.") || name.startsWith("action.")

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
        fun describe(): String = "Intent → $packageName"
    }

    fun buildBroadcast(
        action: Action,
        vars: Map<String, String>,
    ): Broadcast? {
        val spec = action.intent ?: return null
        val extras = mutableMapOf<String, String>()
        spec.optJSONObject("extras")?.let { json -> json.keys().forEach { extras[it] = Template.render(json.getString(it), vars) } }
        val settings = vars.filterKeys(::isSetting)
        val packageName = Template.render(spec.optString("package"), settings).trim()
        require(packageName.matches(packagePattern)) { "Укажите пакет приложения" }
        val intentAction = Template.render(spec.optString("action"), settings).ifBlank { DEFAULT_INTENT_ACTION }
        require(packageName == Template.render(spec.optString("package"), vars).trim() &&
            intentAction == Template.render(spec.optString("action"), vars).ifBlank { DEFAULT_INTENT_ACTION }) {
            "Фраза не может менять получателя команды"
        }
        return Broadcast(
            intentAction,
            packageName,
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
        val method = request.optString("method", "POST").uppercase(Locale.ROOT)
        require(method in httpMethods) { "Этот метод HTTP не поддерживается" }
        val template = request.getString("url")
        val url = Template.render(template, vars, encode = true)
        val origin = origin(url)
        val configured = vars.filterKeys(::isSetting)
        require(origin == origin(Template.render(template, configured, encode = true))) {
            "Фраза не может менять адрес получателя"
        }
        if (origin.scheme == "http") {
            val addressField = leadingSetting.find(template)?.groupValues?.get(1)
            require(addressField != null && configured[addressField]?.startsWith("http://", ignoreCase = true) == true) {
                "Используйте HTTPS или введите HTTP-адрес вручную в настройках команды"
            }
        }
        val headers = mutableMapOf<String, String>()
        request.optJSONObject("headers")?.let { json ->
            json.keys().forEach { name ->
                require(name.lowercase(Locale.ROOT) !in transportHeaders) { "Этот заголовок задаёт Эхо" }
                Template.render(json.getString(name), vars).takeIf { it.isNotBlank() }?.let { headers[name] = it }
            }
        }
        val body = if (method == "GET" || !request.has("body")) null else Template.renderJson(request.get("body"), vars).toString()
        if (body != null && headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/json; charset=utf-8"
        return Request(method, url, headers, body, origin)
    }

    private fun origin(address: String): URI {
        val uri = runCatching { URI(address) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val host = uri?.host?.lowercase(Locale.ROOT)
        require(uri != null && scheme in setOf("https", "http") && host != null &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) {
            "Укажите корректный HTTP- или HTTPS-адрес"
        }
        val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
        return URI(scheme, null, host, port, null, null, null)
    }

    fun describe(context: Context, action: Action, vars: Map<String, String>): String {
        if (action.yandexScenario) return context.getString(R.string.commands_yandex_title)
        action.android?.let { command ->
            return context.getString(R.string.commands_smart_limits, CommandPolicy.current().maximumCharacters, CommandPolicy.current().maximumRecordingSeconds)

        }
        return destination(action, vars).label
    }

    internal fun destination(action: Action, vars: Map<String, String>): Destination =
        buildBroadcast(action, vars)?.let { Destination(it.describe()) }
            ?: build(action, vars).let { Destination(it.describe(), it.cleartext) }

    fun sendCommand(
        context: Context,
        action: Action,
        vars: Map<String, String>,
        blockOnLockScreen: Boolean,
        probe: Boolean = false,
        capturedAtMs: Long? = null,
        expiresAtMs: Long = 0,
        beforeSend: () -> Unit = {},
    ): Result {
        fun checkScreenLock() = checkCommandScreenLock(blockOnLockScreen) {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        }
        return try {
            checkScreenLock()
            if (action.yandexScenario) {
                if (probe && YandexHome.enabled) return Result(true, 0, context.getString(R.string.commands_yandex_title), null)
                return YandexHome.send(context, vars["action.${YandexHome.TARGET}"].orEmpty(), expiresAtMs,
                    checkScreenLock = ::checkScreenLock, beforeSend = beforeSend)
            }
            action.android?.let { command ->
                if (probe) return if (command.kind in setOf(AndroidCommand.Kind.EVENT, AndroidCommand.Kind.EVENT_DRAFT, AndroidCommand.Kind.REMINDER)) {
                    EventDraft.checkCalendar(context, vars["action.${EventCalendars.TARGET}"].orEmpty())
                } else Result(true, 0, context.getString(R.string.commands_smart_probe), null)
                return SmartCommand.send(context, command, vars, capturedAtMs, ::checkScreenLock)
            }
            try {
                val broadcast = buildBroadcast(action, vars)
                if (broadcast != null) {
                    checkScreenLock()
                    send(context, broadcast)
                } else send(build(action, vars), action.reply, ::checkScreenLock)
            } catch (error: IllegalArgumentException) {
                Result(false, 0, null, error.message ?: "Неверный адрес")
            }
        } catch (_: CommandBlockedByScreenLock) {
            Result(false, 0, null, context.getString(R.string.commands_lock_screen_blocked))
        }
    }

    fun send(
        request: Request,
        replyPath: String?,
        checkScreenLock: () -> Unit = {},
    ): Result {
        var last = Result(false, 0, null, "нет связи")
        val readOnly = request.method in setOf("GET", "HEAD")
        repeat(if (readOnly) 2 else 1) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_PAUSE_MS)
            try {
                return NetworkPolicy.request(request.url) {
                    checkScreenLock()
                    exchange(it, request, replyPath)
                }
            } catch (error: com.govorun.echo.NetworkGate.Refused) {
                return Result(false, 0, null, when (error.reason) {
                    com.govorun.echo.NetworkGate.ROAMING -> "Офлайн в роуминге"
                    com.govorun.echo.NetworkGate.OFFLINE -> "Включён «Офлайн»"
                    else -> "нет связи"
                })
            } catch (error: IOException) {
                last = Result(false, 0, null, if (readOnly) "нет связи" else "Ответ сервера не получен", uncertain = !readOnly)
            } catch (error: IllegalArgumentException) {
                return Result(false, 0, null, "неверный адрес")
            }
        }
        return last
    }

    internal fun exchange(connection: HttpURLConnection, request: Request, replyPath: String?): Result {
        connection.requestMethod = request.method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (request.body != null) {
            connection.doOutput = true
            val bytes = request.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val answer = stream?.use { it.readNBytes(MAX_ANSWER).toString(Charsets.UTF_8) }.orEmpty()
        val reply = if (code in 200..299 && replyPath != null) Template.pick(answer, replyPath) else null
        return Result(code in 200..299, code, reply?.take(MAX_REPLY), if (code in 200..299) null else "ошибка $code")
    }

    const val DEFAULT_INTENT_ACTION = "com.govorun.echo.action.COMMAND"
    private val leadingSetting = Regex("^\\{\\{\\s*((?:field|action)\\.[A-Za-z0-9_.]+)\\s*\\}\\}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val httpMethods = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
    private val transportHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
    private const val TIMEOUT_MS = 8_000
    private const val RETRY_PAUSE_MS = 3_000L
    private const val MAX_ANSWER = 64 * 1024
    private const val MAX_REPLY = 160
}
