package com.govorun.echo.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The buttons of «Заметка готова» that act without opening Echo: «Удалить» for now. */
class NoticeActions : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val noteId = SavedNotice.noteExtra(intent) ?: return
        if (intent.action != DELETE) return
        val pending = goAsync()
        Thread {
            try {
                SavedNotice.delete(context, noteId)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val DELETE = "com.govorun.echo.action.DELETE_NOTE"
    }
}
