package com.govorun.echo.commands

import com.govorun.echo.NetworkPolicy
import org.json.JSONObject

internal data class CommandPolicy(val maximumCharacters: Int = 400, val maximumRecordingSeconds: Int = 30, val timeoutSeconds: Int = 15, val credits: Int = 1) {
    companion object {
        @Volatile private var current = CommandPolicy()
        @Volatile private var fetchedAt = 0L
        fun current(): CommandPolicy = current
        fun refresh(): CommandPolicy {
            if (NetworkPolicy.state.value.reason != null || android.os.SystemClock.elapsedRealtime() - fetchedAt in 0..60_000L && fetchedAt > 0) return current
            return runCatching {
                NetworkPolicy.request("https://styles.govorun.club/v1/command-policy") { connection ->
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    val json = JSONObject(connection.inputStream.use { it.readNBytes(2048).toString(Charsets.UTF_8) })
                    val policy = CommandPolicy(json.getInt("maximumCharacters"), json.getInt("maximumRecordingSeconds"), json.getInt("timeoutSeconds"), json.getInt("credits"))
                    require(policy.maximumCharacters in 60..600 && policy.maximumRecordingSeconds in 5..60 && policy.timeoutSeconds in 5..20 && policy.credits == 1)
                    current = policy
                    fetchedAt = android.os.SystemClock.elapsedRealtime()
                    policy
                }
            }.getOrDefault(current)
        }
    }
}
