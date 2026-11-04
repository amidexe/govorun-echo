package com.govorun.echo.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Fingerprint
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.product.ui.productButtonColors

/**
 * «Вход по отпечатку или коду», off unless the person turns it on: opening Echo asks the phone's own fingerprint or screen code
 * (Android's window, no password of Echo's), after Echo has been away more than a minute or on a fresh start. Recording stays
 * open without it — the volume key, the tile and the lock-screen entries live outside this window — so a thought is caught at
 * once; listening and reading wait for the unlock. While the lock is on, Echo's picture in the recent apps is hidden.
 */
internal object AppLock {
    var locked by mutableStateOf(false)
    private var leftAtMs = 0L

    /** Away longer than this, Echo asks again; a quick look at another app does not. */
    private const val GRACE_MS = 60_000L

    /** Whether the phone has a screen lock at all: without one there is nothing to ask. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun onStart(activity: Activity) {
        val on = AppSettings.appLock(activity) && available(activity)
        if (on && (leftAtMs == 0L || System.currentTimeMillis() - leftAtMs > GRACE_MS)) locked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(!on)
    }

    fun onStop() {
        leftAtMs = System.currentTimeMillis()
    }

    /** Android's own fingerprint-or-code window; [onDone] gets whether the person is the phone's owner. */
    fun ask(
        activity: Activity,
        onDone: (Boolean) -> Unit,
    ) {
        // Should the window not come up at all (a phone that refuses it), Echo says so and stays open rather than closing.
        val prompt =
            runCatching {
                BiometricPrompt
                    .Builder(activity)
                    .setTitle(activity.getString(R.string.lock_prompt_title))
                    .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
                    .build()
            }.getOrElse {
                android.util.Log.w("AppLock", "event=prompt_failed", it)
                android.widget.Toast.makeText(activity, R.string.lock_failed, android.widget.Toast.LENGTH_LONG).show()
                return onDone(false)
            }
        runCatching { prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onDone(true)

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence?,
                ) = onDone(false)
            },
        ) }.onFailure {
            android.util.Log.w("AppLock", "event=authenticate_failed", it)
            android.widget.Toast.makeText(activity, R.string.lock_failed, android.widget.Toast.LENGTH_LONG).show()
            onDone(false)
        }
    }
}

/** The app's content, or the lock in front of it while Echo waits for the owner's finger or code. */
@Composable
internal fun AppLockGate(content: @Composable () -> Unit) {
    val activity = LocalContext.current as Activity
    LifecycleEventEffect(Lifecycle.Event.ON_START) { AppLock.onStart(activity) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { AppLock.onStop() }
    if (!AppLock.locked) {
        content()
        return
    }
    val ask = { AppLock.ask(activity) { ok -> if (ok) AppLock.locked = false } }
    // The window comes up by itself once; the button is there when it was dismissed.
    LaunchedEffect(Unit) { ask() }
    // A screen of its own in Echo's colours: the app's mark, its name, one line and the fingerprint button.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_echo_art), contentDescription = null, modifier = Modifier.size(88.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.lock_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            androidx.compose.foundation.layout.Spacer(Modifier.size(32.dp))
            Button(onClick = ask, colors = productButtonColors()) {
                Icon(PhosphorIcons.Regular.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.lock_open))
            }
        }
    }
}

/** The switch, in Settings and on the last step of the first run: turning it on or off asks the finger or code first. */
@Composable
internal fun AppLockSetting() {
    val context = LocalContext.current
    val activity = context as? Activity
    var on by remember { mutableStateOf(AppSettings.appLock(context)) }
    val available = remember { AppLock.available(context) }
    SwitchRow(PhosphorIcons.Regular.Fingerprint, R.string.lock_setting, if (available) R.string.lock_setting_text else R.string.lock_setting_no_screen_lock, on) { want ->
        if (available && activity != null) {
            AppLock.ask(activity) { ok ->
                if (ok) {
                    on = want
                    AppSettings.setAppLock(context, want)
                }
            }
        }
    }
}
