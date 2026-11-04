package com.govorun.echo.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.govorun.echo.BuildConfig
import java.security.MessageDigest

/** Finds the installed Govorun and checks that it is signed by the real owner. */
internal object GovorunProvider {
    const val SERVICE_ACTION = "com.govorun.api.RECOGNITION_SERVICE"
    const val SMART_ACTION = "com.govorun.api.smart.v1.BIND"
    const val PACKAGE = "com.govorun.lite"
    private const val DEBUG_PACKAGE = "com.govorun.lite.debug"
    private const val PRODUCTION_SIGNER = "190e293847873495c5b79d203650bae2b9f7c90ba1a27648fa203ab88cff6d42"

    /** Govorun's service answering [action] (recognition by default, or the smart functions' [SMART_ACTION]), if it is the real Govorun. */
    fun resolve(
        context: Context,
        action: String = SERVICE_ACTION,
    ): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(action), PackageManager.MATCH_ALL)
        services.firstOrNull { it.serviceInfo.packageName == PACKAGE }?.serviceInfo?.let { info ->
            if (PRODUCTION_SIGNER in signerHistory(context)) return ComponentName(info.packageName, info.name)
        }
        // Only the debug build of this app also talks to the debug build of Govorun (testing on an emulator); a store build never does.
        if (BuildConfig.DEBUG) {
            services.firstOrNull { it.serviceInfo.packageName == DEBUG_PACKAGE }?.serviceInfo?.let { return ComponentName(it.packageName, it.name) }
        }
        return null
    }

    private fun signerHistory(context: Context): Set<String> =
        runCatching {
            withSigners(context.packageManager)
                .signingInfo
                ?.signingCertificateHistory
                ?.mapTo(linkedSetOf()) { certificate ->
                    MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
                }.orEmpty()
        }.getOrDefault(emptySet())

    /** Govorun's package with its signing certificates: flags as an object since Android 13, Android 12 takes them as a number. */
    private fun withSigners(packageManager: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        }
}
