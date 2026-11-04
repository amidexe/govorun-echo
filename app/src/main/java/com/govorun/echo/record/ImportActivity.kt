package com.govorun.echo.record

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.AppEntry

/** Receives "Share" (one file or several) and "Open with" for audio or video, hands them to the service and shows the app. */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shared text: a note of text only, opened at once; no sound, nothing to recognise.
        val text = if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (!text.isNullOrBlank()) {
            runCatching {
                val id = com.govorun.echo.notes.NoteText.create(com.govorun.echo.notes.NoteStore.root(this), text, intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
                startActivity(SavedNotice.open(this, id, byUser = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.util.Log.w("Echo", "event=text_share_failed", it) }
            finish()
            return
        }
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(sharedFile())
                Intent.ACTION_SEND_MULTIPLE -> sharedFiles()
                else -> listOfNotNull(intent.data)
            }
        if (uris.isNotEmpty() && FileImport.start(this, uris)) {
            startActivity(AppEntry.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    private fun sharedFiles(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
        }

    /** The typed call came in Android 13 but is unreliable there, so as in AndroidX's IntentCompat it is used from Android 14 on. */
    private fun sharedFile(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        }
}
