package com.govorun.echo.smart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Govorun's word that one of Echo's tasks has its answer or waits for something else (protocol 2): the waiting notes are brought
 * up to date, and an answer is collected under the same task id at no new cost. Echo may be closed; anyone may send it, and all
 * it does is ask Govorun.
 */
class TasksChanged : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!Smart.available || intent.action != ACTION) return
        intent.getStringExtra("taskId")?.let { results.tryEmit(it) }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FairQueue.sync(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.govorun.api.smart.v1.TASKS_CHANGED"
        internal val results = MutableSharedFlow<String>(extraBufferCapacity = 8)
    }
}
