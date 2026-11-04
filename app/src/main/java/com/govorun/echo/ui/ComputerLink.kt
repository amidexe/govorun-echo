package com.govorun.echo.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.govorun.echo.R
import com.govorun.echo.web.ComputerSelection
import com.govorun.echo.web.ComputerSession
import com.govorun.echo.web.WebExport
import kotlinx.coroutines.launch

/** The menu, or a debug run with the code's text (adb, the emulator has no camera to point), asks to send notes to a computer. */
internal object ComputerRequest {
    /** A new value starts the flow; the text of a code skips the camera. */
    var asked by mutableStateOf<Asked?>(null)

    fun accept(intent: android.content.Intent) {
        if (intent.getBooleanExtra("echo_computer", false)) asked = Asked()
        if (!com.govorun.echo.BuildConfig.DEBUG) return
        intent.getStringExtra("echo_pair")?.let { code ->
            val selection = intent.getStringExtra("echo_scope")?.let { raw -> runCatching { ComputerSelection.read(org.json.JSONObject(raw)) }.getOrElse { ComputerSelection(emptySet()) } } ?: ComputerSelection()
            asked = Asked(code, selection)
        }
    }

    class Asked(val code: String? = null, val selection: ComputerSelection = ComputerSelection())
}

/**
 * Echo on a computer: the camera reads the code on the computer's screen (on the phone, [QrScanner]), the owner says yes, and the tab
 * shows the notes, kept up to date while it is open ([ComputerSession]). Nothing happens without that yes, so a code someone else
 * shows never takes the notes by itself. The menu opens [ComputerSheet] first: how to do it, and the open window, if any, to close.
 */
@Composable
internal fun ComputerLink() {
    val asked = ComputerRequest.asked ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val network by com.govorun.echo.NetworkPolicy.state.collectAsState()
    var code by remember(asked) { mutableStateOf(asked.code) }
    var selection by remember(asked) { mutableStateOf(asked.selection) }
    var sending by remember(asked) { mutableStateOf(false) }
    val close = { ComputerRequest.asked = null }
    var scanning by remember(asked) { mutableStateOf(false) }
    if (code == null && !scanning) {
        ComputerSheet(
            onScan = { chosen -> selection = chosen; scanning = true },
            onSendNow = { ComputerSession.sendNow() },
            onCloseWindow = { ComputerSession.close() },
            onDismiss = close,
        )
        return
    }
    if (code == null) {
        CameraGate(onDenied = close) { QrScanner(accept = { WebExport.parse(it) != null }, onCode = { code = it }, onClose = close) }
        return
    }
    val pairing = remember(code) { code?.let(WebExport::parse) }
    if (pairing == null) {
        LaunchedEffect(code) {
            Toast.makeText(context, R.string.computer_bad_code, Toast.LENGTH_LONG).show()
            close()
        }
        return
    }
    val target = pairing
    AlertDialog(
        onDismissRequest = { if (!sending) close() },
        title = { Text(stringResource(R.string.computer_confirm_title)) },
        text = { androidx.compose.foundation.layout.Column {
            Text(stringResource(R.string.computer_confirm_text) + "\n\n" +
                stringResource(R.string.computer_scope, selection.tags?.sorted()?.joinToString(", ") ?: stringResource(R.string.computer_all)))
        } },
        confirmButton = {
            if (network.reason != null) NetworkNotice(network.reason!!, androidx.compose.ui.Modifier.widthIn(max = 220.dp))
            else TextButton(
                enabled = !sending && network.reason == null,
                onClick = {
                    sending = true
                    scope.launch {
                        if (ComputerSession.start(context, target, deviceName(context), selection)) {
                            code = null
                            scanning = false
                            sending = false
                        } else {
                            Toast.makeText(context, R.string.computer_failed, Toast.LENGTH_LONG).show()
                            sending = false
                        }
                    }
                },
            ) { Text(stringResource(if (sending) R.string.computer_sending else R.string.computer_send)) }
        },
        dismissButton = { TextButton(onClick = close, enabled = !sending) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The camera is asked for the first time a code is to be read, and only then; without it there is nothing to aim. */
@Composable
private fun CameraGate(
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                granted = true
            } else {
                Toast.makeText(context, R.string.computer_no_camera, Toast.LENGTH_LONG).show()
                onDenied()
            }
        }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) content()
}

/** What the page shows as the phone on the line: the name the owner gave the phone, else its model. */
private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
