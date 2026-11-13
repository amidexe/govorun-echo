package com.govorun.echo.ui

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.core.view.WindowCompat
import com.govorun.echo.AppEntry

/** Keep the real backdrop behind the system bars. The Compose controls use safe insets separately. */
@Suppress("DEPRECATION")
internal fun Window.prepareCaptureInsets() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    clearFlags(LayoutParams.FLAG_TRANSLUCENT_STATUS or LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    navigationBarDividerColor = Color.TRANSPARENT
    isStatusBarContrastEnforced = false
    isNavigationBarContrastEnforced = false
    attributes = attributes.apply {
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        setFitInsetsSides(0)
    }
    WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars = false
}

/** Only the bottom panel animates; there is no separate task transition. */
internal fun Activity.prepareCaptureTransition() {
    window.prepareCaptureInsets()
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** Only the recording card can be shown over keyguard. The note opens after Android unlocks. */
internal fun Activity.openCaptureNote(noteId: String, onUnlockAborted: () -> Unit = {}) {
    val open = {
        if (!isFinishing && !isDestroyed) {
            startActivity(AppEntry.note(this, noteId))
            finish()
        }
    }
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (keyguard?.isKeyguardLocked == true) {
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
            override fun onDismissCancelled() = onUnlockAborted()
            override fun onDismissError() = onUnlockAborted()
        })
    } else {
        open()
    }
}
