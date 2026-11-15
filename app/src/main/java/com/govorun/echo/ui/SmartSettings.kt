package com.govorun.echo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.SignIn
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import com.product.ui.ProductDivider
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import kotlinx.coroutines.launch

/**
 * Settings → «Умные функции»: one feature switch and optional automation. Manual tools and style selection
 * stay in the editor. Echo's Offline switch controls network use; account and consent remain Govorun's responsibility.
 */
@Composable
internal fun FairSettings() {
    val context = LocalContext.current
    if (!Smart.available) {
        SettingsInfoRow(PhosphorIcons.Regular.Sparkle, stringResource(R.string.fair_action), stringResource(R.string.fair_settings_coming))
        return
    }
    val selected by Smart.optedIn.collectAsState()
    SwitchRow(PhosphorIcons.Regular.Sparkle, R.string.smart_enable_title, R.string.smart_enable_text, selected) {
        if (!Smart.choose(context, it)) android.widget.Toast.makeText(context, R.string.smart_choice_save_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
    if (!selected) return
    ProductDivider()
    EnabledSmartSettings()
}

@Composable
private fun EnabledSmartSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(AppSettings.itogiAuto(context)) }
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Read again on every return, so signing in to Govorun and coming back opens the tab at once.
    LifecycleResumeEffect(auto) {
        val job = scope.launch { step = GovorunSmart(context).status().let { if (auto) it.automaticState else it.state }.takeUnless { it == GovorunSmart.READY } }
        onPauseOrDispose { job.cancel() }
    }
    val network by com.govorun.echo.NetworkPolicy.state.collectAsState()
    val wait = if (step == GovorunSmart.UPDATE || step == GovorunSmart.MISSING) step else network.reason ?: step
    if (wait != null && !isOfflineReason(wait)) {
        val text = smartStateText(wait)
        if (text.action != null) {
            SettingsLinkRow(PhosphorIcons.Regular.SignIn, stringResource(text.action), stringResource(text.line), onClick = { scope.launch { openGovorunFor(context, wait) } })
        } else {
            SettingsInfoRow(PhosphorIcons.Regular.Info, stringResource(text.line))
        }
        ProductDivider()
    }
    SwitchRow(PhosphorIcons.Regular.Sparkle, R.string.itogi_auto_title, R.string.itogi_auto_text, auto) {
        auto = it
        AppSettings.setItogiAuto(context, it)
        com.govorun.echo.smart.FairQueue.schedule(context)
    }
    if (isOfflineReason(wait)) {
        ProductDivider()
        NetworkNotice(
            checkNotNull(wait),
            androidx.compose.ui.Modifier.padding(
                horizontal = com.product.ui.ProductTokens.Spacing.Section,
                vertical = com.product.ui.ProductTokens.Spacing.Lg,
            ),
            compact = true,
        )
    }
}
