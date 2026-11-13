package com.govorun.echo.commands

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore

/** Opened by the system's authenticated notification action. */
class CommandOpenActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val noteId = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true && target != null) {
            Notice.cancelOpen(this, noteId)
            val error = AndroidCommand.openNow(this, target)
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            val dir = NoteStore.noteDir(this, noteId)
            if (noteId.isNotBlank() && dir.isDirectory) {
                NoteStore.updateMeta(dir) {
                    val command = optJSONObject("command")
                    if (command?.optString("label") == getString(R.string.commands_android_deferred)) {
                        command.put("ok", error == null).put("label", Runner.Result(error == null, 0, null, error).label())
                    }
                }
            }
        }
        finish()
    }

    companion object {
        internal const val EXTRA_TARGET = "android_command_target"
        private const val EXTRA_NOTE = "android_command_note"

        internal fun intent(context: Context, noteId: String) = Intent(context, CommandOpenActivity::class.java)
            .setIdentifier("echo-command:$noteId")
            .putExtra(EXTRA_NOTE, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
