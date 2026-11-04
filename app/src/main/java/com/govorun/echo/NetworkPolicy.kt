package com.govorun.echo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local network choices, as in Govorun. No timer, network service or per-feature switches. */
internal object NetworkPolicy {
    data class State(val offline: Boolean = false, val noRoaming: Boolean = true, val reason: String? = NetworkGate.NO_NETWORK)
    private val current = MutableStateFlow(State())
    val state: StateFlow<State> get() = current
    private val revisions = MutableStateFlow(0)
    val changes: StateFlow<Int> get() = revisions
    private val gate = NetworkGate()
    private val lock = Any()
    private lateinit var app: Context
    private var route: Network? = null
    private var published: Pair<Network?, State>? = null
    private var capabilities: NetworkCapabilities? = null

    fun initialize(context: Context) = synchronized(lock) {
        if (::app.isInitialized) return@synchronized
        app = context.applicationContext
        val prefs = app.getSharedPreferences("device_network", Context.MODE_PRIVATE)
        current.value = State(prefs.getBoolean("offline", false), prefs.getBoolean("no_roaming", true))
        val manager = app.getSystemService(ConnectivityManager::class.java)
        route = manager.activeNetwork
        capabilities = route?.let(manager::getNetworkCapabilities)
        publish()
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(lock) { route = network; capabilities = null; publish() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = synchronized(lock) {
                if (route == network) { capabilities = caps; publish() }
            }
            override fun onLost(network: Network) = synchronized(lock) {
                if (route == network) { route = null; capabilities = null; publish() }
            }
        })
    }

    fun setOffline(value: Boolean): Boolean = change("offline", value)
    fun setNoRoaming(value: Boolean): Boolean = change("no_roaming", value)
    private fun change(key: String, value: Boolean): Boolean = synchronized(lock) {
        val prefs = app.getSharedPreferences("device_network", Context.MODE_PRIVATE)
        if (!prefs.edit().putBoolean(key, value).commit()) return@synchronized false
        current.value = current.value.copy(offline = prefs.getBoolean("offline", false), noRoaming = prefs.getBoolean("no_roaming", true))
        publish()
        true
    }

    private fun publish() {
        val old = current.value
        val reason = NetworkGate.refusal(old.offline, old.noRoaming, route != null && capabilities != null,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true)
        val next = old.copy(reason = reason)
        if (published == (route to next)) return
        if (published?.first != route || published?.second?.reason != reason) gate.change(reason)
        published = route to next
        current.value = next
        revisions.value++
    }

    fun requireAllowed() { gate.allowed {} }
    /** Binder hand-in is local: absent connectivity may queue a task, an explicit refusal may not. */
    fun <T> submit(block: () -> T): T = synchronized(lock) {
        current.value.reason?.takeIf(NetworkGate::isUserRefusal)
            ?.let { throw NetworkGate.Refused(it) }
        block()
    }

    fun <T> request(url: String, block: (HttpURLConnection) -> T): T {
        val connection = synchronized(lock) {
            gate.allowed { checkNotNull(route).openConnection(URL(url)) as HttpURLConnection }
        }
        return gate.begin(connection::disconnect).use { lease ->
            try {
                lease.check()
                block(connection).also { lease.check() }
            } finally { connection.disconnect() }
        }
    }
}
