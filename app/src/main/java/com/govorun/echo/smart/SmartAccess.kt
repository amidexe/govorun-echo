package com.govorun.echo.smart

import java.io.IOException

/** Echo's own choice, independent of Govorun's account/consent and of local recognition. */
internal class SmartAccess(private val changed: (Boolean) -> Unit = {}) {
    private var choice = false
    private var saving = false

    @get:Synchronized
    val selected: Boolean get() = choice

    @Synchronized fun clientOffline(offline: Boolean): Boolean = offline || !choice

    /** Includes restored preferences; a synchronous preference callback cannot enable a failed write. */
    @Synchronized fun refresh(value: Boolean) {
        if (!saving) publish(value)
    }

    @Synchronized fun choose(value: Boolean, persist: () -> Boolean): Boolean {
        saving = true
        val saved = try { persist() } catch (_: Exception) { false } finally { saving = false }
        publish(value && saved)
        return saved
    }

    /** Serialized with choice changes, including retries and the final Binder hand-in. */
    @Synchronized fun <T> submit(send: () -> T): T {
        if (!choice) throw Refused()
        return send()
    }

    private fun publish(value: Boolean) {
        choice = value
        changed(value)
    }

    class Refused : IOException(DISABLED)

    companion object { const val DISABLED = "echo_smart_disabled" }
}
