package com.govorun.echo.ui

import android.app.ActivityOptions
import android.content.Context
import android.os.Build
import com.govorun.echo.R
import com.govorun.echo.smart.GovorunSmart

/**
 * What a Govorun state means for a person in Echo (docs/SMART_PLAN.md section 3): one line, and the action of Govorun that settles
 * it — the same words and buttons as Govorun's own access card, so both apps say one thing.
 */
internal class SmartStateText(
    val line: Int,
    val action: Int? = null,
    /** The screen of Govorun the action opens ([GovorunSmart.action]); null for one Echo handles itself. */
    val govorunAction: String? = null,
)

internal fun smartStateText(state: String): SmartStateText =
    when (state) {
        com.govorun.echo.smart.SmartAccess.DISABLED -> SmartStateText(R.string.smart_state_disabled)
        "queue_recovered" -> SmartStateText(R.string.smart_queue_recovered)
        "queue_storage" -> SmartStateText(R.string.smart_queue_storage)
        "result_missing" -> SmartStateText(R.string.smart_result_missing)
        "result_changed" -> SmartStateText(R.string.smart_result_changed)
        com.govorun.echo.NetworkGate.OFFLINE -> SmartStateText(R.string.network_offline_blocked)
        com.govorun.echo.NetworkGate.ROAMING -> SmartStateText(R.string.network_roaming_blocked)
        GovorunSmart.READY -> SmartStateText(R.string.smart_state_ready)
        GovorunSmart.UPDATE -> SmartStateText(R.string.smart_state_update)
        GovorunSmart.MISSING -> SmartStateText(R.string.smart_state_missing)
        GovorunSmart.NOT_SIGNED_IN -> SmartStateText(R.string.smart_state_sign_in)
        GovorunSmart.NOT_AUTHORIZED -> SmartStateText(R.string.smart_state_not_authorized, R.string.smart_action_authorize, "authorize")
        GovorunSmart.NEEDS_ACCESS -> SmartStateText(R.string.smart_state_needs_access, R.string.smart_action_access, "subscription")
        GovorunSmart.STYLIZATION_TERMS -> SmartStateText(R.string.fair_terms, R.string.smart_action_terms, GovorunSmart.ACTION_STYLIZATION)
        GovorunSmart.OFFLINE -> SmartStateText(R.string.smart_state_offline)
        GovorunSmart.ROAMING -> SmartStateText(R.string.smart_state_roaming)
        GovorunSmart.NO_NETWORK -> SmartStateText(R.string.smart_state_no_network)
        GovorunSmart.LIMIT_TODAY -> SmartStateText(R.string.smart_state_limit)
        GovorunSmart.LIMIT_PERIOD -> SmartStateText(R.string.smart_state_limit_period)
        GovorunSmart.TOO_SHORT -> SmartStateText(R.string.fair_too_short)
        GovorunSmart.TOO_LONG -> SmartStateText(R.string.fair_too_long)
        "styles_missing" -> SmartStateText(R.string.fair_styles_missing)
        else -> SmartStateText(R.string.smart_state_unavailable)
    }

/** Opens the screen of Govorun that settles [state], if it has one; false when there is none or Govorun did not give it. */
internal suspend fun openGovorunFor(
    context: Context,
    state: String,
): Boolean {
    val name = smartStateText(state).govorunAction ?: return false
    val opened = openGovorunAction(context, name)
    if (!opened) android.widget.Toast.makeText(context, R.string.smart_action_failed, android.widget.Toast.LENGTH_SHORT).show()
    return opened
}

/** Opens a screen of Govorun by its action name; false when Govorun did not give it. */
internal suspend fun openGovorunAction(
    context: Context,
    name: String,
): Boolean {
    val intent = GovorunSmart(context).action(name) ?: return false
    // Android 14+ lets an app's screen open from another app's PendingIntent only when the sender, visible on screen, says so.
    val options =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else {
            null
        }
    return runCatching { intent.send(context, 0, null, null, null, null, options) }.isSuccess
}
