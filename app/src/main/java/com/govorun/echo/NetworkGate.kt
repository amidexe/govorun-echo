package com.govorun.echo

import java.io.Closeable
import java.io.IOException

/** One permission boundary for requests and their cancellation, including a change of route. */
internal class NetworkGate {
    private val lock = Any()
    private var generation = 0L
    private var reason: String? = NO_NETWORK
    private val active = mutableSetOf<Lease>()

    fun change(reason: String?) {
        val stop = synchronized(lock) {
            this.reason = reason
            generation++
            active.toList().also { active.clear() }
        }
        stop.forEach { it.cancel() }
    }

    fun <T> allowed(block: () -> T): T = synchronized(lock) {
        reason?.let { throw Refused(it) }
        block()
    }

    fun begin(cancel: () -> Unit): Lease = allowed {
        Lease(generation, cancel).also { active.add(it) }
    }

    inner class Lease(private val epoch: Long, private val stop: () -> Unit) : Closeable {
        fun check() = synchronized(lock) {
            if (epoch != generation) throw Refused(reason ?: NO_NETWORK)
            reason?.let { throw Refused(it) }
        }
        internal fun cancel() { runCatching(stop) }
        override fun close() { synchronized(lock) { active.remove(this) } }
    }

    class Refused(val reason: String) : IOException(reason)

    companion object {
        const val OFFLINE = "echo_offline"
        const val ROAMING = "echo_roaming"
        const val NO_NETWORK = "no_network"
        fun isUserRefusal(reason: String?): Boolean = reason == OFFLINE || reason == ROAMING
        fun refusal(offline: Boolean, noRoaming: Boolean, connected: Boolean, notRoaming: Boolean): String? = when {
            offline -> OFFLINE
            !connected -> NO_NETWORK
            noRoaming && !notRoaming -> ROAMING
            else -> null
        }
    }
}
