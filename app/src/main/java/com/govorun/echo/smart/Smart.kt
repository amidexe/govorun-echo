package com.govorun.echo.smart

import android.content.Context
import com.govorun.echo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.govorun.echo.AppSettings

/** Optional online text tasks through Govorun's account, consent, network policy and limits.
 * Echo applies its common network choice; originals and completed results remain available offline. */
internal object Smart {
    private val choice = MutableStateFlow(false)
    val optedIn: StateFlow<Boolean> get() = choice
    private val access = SmartAccess { choice.value = it }
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val changes: StateFlow<Pair<Int, Boolean>> = combine(com.govorun.echo.NetworkPolicy.changes, choice) { network, selected -> network to selected }
        .stateIn(scope, SharingStarted.Eagerly, 0 to false)
    @Synchronized fun initialize(context: Context) {
        if (listener == null) listener = AppSettings.observeSmartFunctions(context.applicationContext, access::refresh)
    }
    fun choose(context: Context, value: Boolean): Boolean = access.choose(value) { AppSettings.setSmartFunctions(context, value) }
    fun chosen(): Boolean = available && access.selected
    fun clientOffline(offline: Boolean): Boolean = !available || access.clientOffline(offline)
    val available: Boolean get() = BuildConfig.HAS_SMART
    fun disabledReason(): String = if (!chosen()) SmartAccess.DISABLED else com.govorun.echo.NetworkPolicy.state.value.reason ?: com.govorun.echo.NetworkGate.OFFLINE
    fun enabled(): Boolean = chosen() && !com.govorun.echo.NetworkGate.isUserRefusal(com.govorun.echo.NetworkPolicy.state.value.reason)
    fun <T> submit(send: () -> T): T = try {
        if (!available) throw SmartAccess.Refused()
        access.submit { com.govorun.echo.NetworkPolicy.submit(send) }
    } catch (_: SmartAccess.Refused) { throw GovorunSmart.Refusal(SmartAccess.DISABLED)
    } catch (refused: com.govorun.echo.NetworkGate.Refused) { throw GovorunSmart.Refusal(refused.reason) }

    /**
     * A stand-in for Govorun on a test emulator, set only by the debug build's DebugActivity: ready answers from the phone itself,
     * no model and no network, so the screens can be checked without Govorun. Never set in a beta or a release.
     */
    @Volatile var stand: Writer? = null

    /** Govorun's text improvement for «Улучшить через Говорун»: the [stand]'s ready answer on a test emulator, otherwise Govorun. */
    fun improver(context: Context): (taskId: String, text: String, style: String?, asked: Boolean) -> String {
        stand?.let { stand -> return { _, text, style, _ -> submit { stand.write("text.improve", org.json.JSONObject().put("text", text).put("style", style)) } } }
        val govorun = GovorunSmart(context)
        return { taskId, text, style, asked -> runBlocking(Dispatchers.IO) { govorun.textTask("text.improve", text, "note", taskId, asked, style) } }
    }

    /** Keep the reason when styles cannot be offered; the UI must not invent a settings action. */
    suspend fun styleStatus(context: Context): GovorunSmart.Status {
        if (!chosen()) throw GovorunSmart.Refusal(SmartAccess.DISABLED)
        if (stand != null) return GovorunSmart.Status(GovorunSmart.READY, styles = listOf(GovorunSmart.Style("light", "Лёгкая правка"), GovorunSmart.Style("formal", "Связно"), GovorunSmart.Style("concise", "Короче")))
        return GovorunSmart(context).status()
    }
}
