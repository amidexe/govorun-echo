package com.govorun.echo.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.govorun.echo.R
import com.govorun.echo.recognition.GovorunProvider
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsInfoRow
import com.govorun.echo.smart.GovorunSmart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Govorun lends Echo volume-down on the lock screen. Offer Android accessibility
 * settings while the key service is off, and Govorun's permission dialog once it is on.
 * Both states are refreshed each time the screen comes back.
 */
@Composable
internal fun GovorunKeyEntry(onOpenAccessibility: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GovorunSmart.Status?>(null) }
    var keyServiceEnabled by remember { mutableStateOf(false) }
    var showAccessibilityHelp by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(round) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val (current, enabled) = withContext(Dispatchers.IO) {
                val provider = GovorunProvider.resolve(context, GovorunProvider.SMART_ACTION)?.packageName
                val services = context.getSystemService(AccessibilityManager::class.java)
                    ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
                val keyService = services.any { service ->
                    service.resolveInfo.serviceInfo.packageName == provider &&
                        service.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
                }
                GovorunSmart(context).status() to keyService
            }
            keyServiceEnabled = enabled
            status = current
        }
    }
    val s = status ?: return
    if (!s.volumeKeyKnown) {
        SettingsInfoRow(PhosphorIcons.Regular.SpeakerHigh, stringResource(R.string.govorun_key_title), stringResource(R.string.govorun_key_update))
        return
    }
    SettingsLinkRow(
        PhosphorIcons.Regular.SpeakerHigh,
        stringResource(R.string.govorun_key_title),
        stringResource(when {
            !keyServiceEnabled -> R.string.govorun_key_enable_service
            s.volumeKeyGranted -> R.string.govorun_key_on
            else -> R.string.govorun_key_offer
        }),
    ) {
        if (!keyServiceEnabled) {
            showAccessibilityHelp = true
            return@SettingsLinkRow
        }
        scope.launch {
            if (!openGovorunAction(context, GovorunSmart.ACTION_VOLUME_KEY)) {
                android.widget.Toast.makeText(context, R.string.govorun_key_open_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
            round++
        }
    }
    if (showAccessibilityHelp && !keyServiceEnabled) {
        AlertDialog(
            onDismissRequest = { showAccessibilityHelp = false },
            title = { Text(stringResource(R.string.govorun_key_title)) },
            text = { Text(stringResource(R.string.govorun_key_service_help)) },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityHelp = false
                    onOpenAccessibility()
                }) { Text(stringResource(R.string.govorun_key_open_accessibility)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityHelp = false }) {
                    Text(stringResource(R.string.hotkey_consent_cancel))
                }
            },
        )
    }
}
