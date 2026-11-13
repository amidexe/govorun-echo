package com.govorun.echo.commands

import android.content.Context
import android.net.Uri
import android.util.Log
import com.govorun.echo.BuildConfig
import com.govorun.echo.NetworkGate
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.R
import com.govorun.echo.SecretStore
import java.io.IOException
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject

internal object YandexHome {
    const val RECIPE = "yandex-home"
    const val ACTION = "scenario"
    const val TARGET = "scenario"
    const val TARGET_NAME = "scenario_name"
    val enabled: Boolean get() = BuildConfig.HAS_YANDEX_HOME
    private const val API = "https://api.iot.yandex.net/v1.0"
    private const val TIMEOUT = 4_000
    private const val MAX_INFO = 2 * 1024 * 1024
    private const val CONNECTION = "connection"
    private val scenarioId = Regex("[A-Za-z0-9_-]{1,128}")

    data class Scenario(val id: String, val name: String)
    private class Connection(val token: String, val scenarios: Set<String>)
    class Refused(val messageId: Int) : IOException()

    fun recipe(context: Context): Recipe {
        val name = context.getString(R.string.commands_yandex_title)
        return Recipe(RECIPE, name, emptyList(), listOf(Action(ACTION, name, emptyList(), null, reply = null, yandexScenario = true)))
    }

    private fun secrets(context: Context) = SecretStore(context, "secret_yandex_home", "echo-yandex-home")

    private fun connection(context: Context): Connection? = runCatching {
        val json = JSONObject(secrets(context).get(CONNECTION) ?: return null)
        val ids = json.getJSONArray("scenarios")
        Connection(json.getString("token"), (0 until ids.length()).mapTo(mutableSetOf(), ids::getString))
    }.getOrNull()

    fun connected(context: Context): Boolean = connection(context) != null

    @Synchronized
    fun disconnect(context: Context) = secrets(context).put(CONNECTION, "")

    fun scenarios(context: Context, newToken: String? = null): List<Scenario> {
        if (!enabled) throw Refused(R.string.commands_yandex_paused)
        val current = connection(context)?.token
        val token = newToken?.takeIf(String::isNotBlank) ?: current ?: throw Refused(R.string.commands_yandex_reconnect)
        val scenarios = NetworkPolicy.request("$API/user/info") { http ->
            configure(http, token)
            val reply = response(http, MAX_INFO)
            parseScenarios(reply)
        }
        synchronized(this) {
            // A request started before disconnect must never restore the old connection.
            if (connection(context)?.token != current) throw Refused(R.string.commands_yandex_reconnect)
            val record = JSONObject().put("token", token).put("scenarios", JSONArray(scenarios.map(Scenario::id)))
            secrets(context).put(CONNECTION, record.toString())
        }
        return scenarios
    }

    internal fun parseScenarios(reply: JSONObject): List<Scenario> {
        val items = reply.optJSONArray("scenarios") ?: throw IOException()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.opt("id") as? String ?: return@mapNotNull null
            val name = (item.opt("name") as? String)?.trim().orEmpty()
            if (!item.optBoolean("is_active") || !scenarioId.matches(id) || name.isEmpty()) return@mapNotNull null
            Scenario(id, name)
        }.distinctBy(Scenario::id).sortedBy { it.name.lowercase() }
    }

    fun send(context: Context, id: String, expiresAtMs: Long, checkScreenLock: () -> Unit, beforeSend: () -> Unit): Runner.Result {
        fun failure(message: Int, uncertain: Boolean = false) =
            Runner.Result(false, 0, null, context.getString(message), uncertain = uncertain)
        if (!enabled) return failure(R.string.commands_yandex_paused)
        fun checkDispatch() {
            if (System.currentTimeMillis() >= expiresAtMs) throw Refused(R.string.commands_smart_expired)
            checkScreenLock()
        }
        var dispatched = false
        return try {
            checkDispatch()
            val active = connection(context) ?: throw Refused(R.string.commands_yandex_reconnect)
            if (!scenarioId.matches(id)) throw Refused(R.string.commands_yandex_choose)
            // A cached list can predate an app upgrade or a new scenario in Yandex.
            if (id !in active.scenarios && scenarios(context).none { it.id == id })
                throw Refused(R.string.commands_yandex_choose)
            NetworkPolicy.request("$API/scenarios/${Uri.encode(id)}/actions") { http ->
                configure(http, active.token)
                http.requestMethod = "POST"
                http.doOutput = true
                // Streaming prevents the connection from replaying a buffered POST after an I/O error.
                http.setFixedLengthStreamingMode(0)
                if (connection(context)?.token != active.token) throw Refused(R.string.commands_yandex_reconnect)
                checkDispatch()
                beforeSend()
                checkDispatch()
                dispatched = true
                http.outputStream.close()
                response(http, 8 * 1024)
                Runner.Result(true, 200, null, null)
            }
        } catch (error: Refused) {
            failure(error.messageId)
        } catch (_: IOException) {
            if (dispatched) failure(R.string.commands_smart_unknown, uncertain = true)
            else failure(R.string.commands_smart_offline)
        }
    }

    fun errorMessage(error: Throwable): Int = when (error) {
        is Refused -> error.messageId
        is NetworkGate.Refused -> R.string.commands_smart_offline
        else -> R.string.commands_yandex_load_failed
    }

    private fun configure(http: HttpURLConnection, token: String) {
        http.instanceFollowRedirects = false
        http.connectTimeout = TIMEOUT
        http.readTimeout = TIMEOUT
        http.setRequestProperty("Authorization", "Bearer $token")
    }

    private fun response(http: HttpURLConnection, limit: Int): JSONObject {
        val code = http.responseCode
        if (code == 401 || code == 403) throw Refused(R.string.commands_yandex_reconnect)
        if (code == 404) throw Refused(R.string.commands_yandex_choose)
        if (code in 400..499) throw Refused(R.string.commands_yandex_load_failed)
        if (code !in 200..299) throw IOException()
        val body = http.inputStream.use { it.readNBytes(limit + 1) }
        if (body.size > limit) throw IOException()
        val json = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrElse { throw IOException() }
        json.optString("request_id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
            ?.let { Log.i("Echo", "event=yandex_home_response request_id=$it") }
        if (json.optString("status") != "ok") throw IOException()
        return json
    }
}
