package com.govorun.echo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.R
import com.govorun.echo.commands.Runner
import com.govorun.echo.commands.YandexHome
import com.product.ui.ProductChoiceSheet
import com.product.ui.ProductDivider
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun YandexCommandSetup(
    selectedId: String,
    savedName: String,
    blockOnLockScreen: Boolean,
    onValidated: (Boolean) -> Unit,
    onSelected: (YandexHome.Scenario) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sdk = if (YandexHome.enabled) remember(context) { YandexAuthSdk.create(YandexAuthOptions(context)) } else null
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var scenarios by remember { mutableStateOf(emptyList<YandexHome.Scenario>()) }
    var choosing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var confirmingTest by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    suspend fun load(token: String? = null, choose: Boolean = false) {
        busy = true
        problem = null
        testResult = null
        scenarios = emptyList()
        try {
            scenarios = withContext(Dispatchers.IO) { YandexHome.scenarios(context, token) }
            connected = true
            choosing = choose && scenarios.isNotEmpty()
        } catch (error: java.io.IOException) {
            problem = YandexHome.errorMessage(error)
        } catch (_: java.security.GeneralSecurityException) {
            problem = R.string.commands_yandex_auth_failed
        } finally {
            busy = false
        }
    }

    val auth = if (sdk != null) rememberLauncherForActivityResult(sdk.contract) { result ->
        when (result) {
            is YandexAuthResult.Success -> scope.launch { load(result.token.value, choose = true) }
            is YandexAuthResult.Failure -> {
                problem = R.string.commands_yandex_auth_failed
                busy = false
            }
            YandexAuthResult.Cancelled -> busy = false
        }
    } else null

    LaunchedEffect(Unit) {
        connected = withContext(Dispatchers.IO) { YandexHome.connected(context) }
        if (connected && YandexHome.enabled) load() else busy = false
    }
    val selected = scenarios.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected) {
        onValidated(selected != null)
        testResult = null
    }

    SettingsSection(stringResource(R.string.commands_yandex_account)) {
        if (!YandexHome.enabled) {
            SettingsInfoRow(title = stringResource(R.string.commands_yandex_paused), summary = savedName.takeIf(String::isNotBlank))
        } else if (!connected) {
            SettingsInfoRow(
                title = stringResource(R.string.commands_yandex_title),
                summary = stringResource(R.string.commands_yandex_sign_in_detail),
            )
        } else {
            SettingsInfoRow(
                title = stringResource(R.string.commands_yandex_connected),
                summary = stringResource(R.string.commands_yandex_connected_detail),
            )
        }
        if (auth != null && (!connected || problem == R.string.commands_yandex_reconnect)) {
            SettingsLinkRow(title = stringResource(R.string.commands_yandex_sign_in), enabled = !busy) {
                runCatching {
                    NetworkPolicy.requireAllowed()
                    problem = null
                    busy = true
                    auth.launch(YandexAuthLoginOptions())
                }.onFailure {
                    problem = if (it is java.io.IOException) YandexHome.errorMessage(it) else R.string.commands_yandex_auth_failed
                    busy = false
                }
            }
        }
        if (connected && YandexHome.enabled) {
            ProductDivider()
            SettingsLinkRow(
                title = stringResource(R.string.commands_yandex_scenario),
                summary = selected?.name ?: savedName.ifBlank { stringResource(R.string.commands_yandex_choose) },
                enabled = !busy,
            ) {
                if (scenarios.isNotEmpty()) choosing = true else scope.launch { load(choose = true) }
            }
            if (selected != null) {
                SettingsLinkRow(title = stringResource(R.string.commands_yandex_test), enabled = !busy) {
                    confirmingTest = true
                }
            }
            SettingsLinkRow(title = stringResource(R.string.commands_yandex_update), enabled = !busy) {
                scope.launch { load() }
            }
        }
        if (connected) SettingsLinkRow(title = stringResource(R.string.commands_yandex_disconnect), enabled = !busy) { disconnecting = true }
        val status = when {
            !YandexHome.enabled -> null
            busy -> R.string.commands_yandex_loading
            problem != null -> problem
            connected && scenarios.isEmpty() -> R.string.commands_yandex_empty
            else -> null
        }
        if (status != null) SettingsInfoRow(title = stringResource(status))
        testResult?.let { SettingsInfoRow(title = it) }
    }

    if (choosing && scenarios.isNotEmpty()) {
        ProductChoiceSheet<YandexHome.Scenario?>(
            title = stringResource(R.string.commands_yandex_choose),
            options = scenarios,
            selected = selected,
            optionLabel = { it?.name.orEmpty() },
            onSelected = { it?.let(onSelected); choosing = false },
            onDismissRequest = { choosing = false },
        )
    }
    if (disconnecting) {
        AlertDialog(
            onDismissRequest = { disconnecting = false },
            title = { Text(stringResource(R.string.commands_yandex_disconnect)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.commands_yandex_disconnect_detail))
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://id.yandex.ru/security"))) }
                    }) { Text(stringResource(R.string.commands_yandex_revoke)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    YandexHome.disconnect(context)
                    scenarios = emptyList()
                    connected = false
                    problem = null
                    disconnecting = false
                }) { Text(stringResource(R.string.commands_yandex_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { disconnecting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmingTest && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmingTest = false },
            title = { Text(stringResource(R.string.commands_yandex_test)) },
            text = { Text(stringResource(R.string.commands_yandex_test_confirm, selected.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingTest = false
                    busy = true
                    testResult = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                Runner.sendCommand(context, YandexHome.recipe(context).actions.single(),
                                    mapOf("action.${YandexHome.TARGET}" to selected.id), blockOnLockScreen,
                                    expiresAtMs = System.currentTimeMillis() + 10_000)
                            }
                            testResult = result.error ?: context.getString(R.string.commands_yandex_started)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: RuntimeException) {
                            testResult = context.getString(R.string.commands_smart_unknown)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.commands_yandex_test_run)) }
            },
            dismissButton = { TextButton(onClick = { confirmingTest = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
