package com.govorun.echo.record

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.govorun.echo.R

/**
 * Audio or video files into Echo, from «Поделиться» or the menu's «Добавить файлы»: each goes to the service, which decodes it and
 * queues it (a long file behind recordings and short ones). No question and no message: its card appears in the list at once.
 */
internal object FileImport {
    /** From «Добавить файлы»: the picked files stay readable for the minutes their decoding may take. */
    fun picked(
        context: Context,
        uris: List<Uri>,
    ): Boolean {
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        return start(context, uris)
    }

    fun start(
        context: Context,
        uris: List<Uri>,
    ): Boolean {
        val sent =
            uris.count { uri ->
                runCatching {
                    context.startForegroundService(
                        Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_IMPORT)
                            .setData(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { android.util.Log.w("Echo", "event=import_start_failed", it) }.isSuccess
            }
        // The new card in the list is the answer; a line on top of it only repeated it. Only a file that cannot be read is said.
        if (sent == 0) Toast.makeText(context, R.string.import_failed_start, Toast.LENGTH_LONG).show()
        return sent > 0
    }
}
