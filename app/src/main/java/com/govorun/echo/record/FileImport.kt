package com.govorun.echo.record

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.govorun.echo.R

/**
 * Audio or video files into Echo, from «Поделиться» or the menu's «Добавить файлы»: each goes to the service, which decodes it and
 * queues it (a long file behind recordings and short ones). The mode choice is shared with external file imports.
 */
internal object FileImport {
    const val EXTRA_SPEECH_FILTER = "com.govorun.echo.FILE_SPEECH_FILTER"
    fun start(
        context: Context,
        uris: List<Uri>,
        speechFilter: Boolean = false,
    ): Boolean {
        // Android's media decoders can fetch a URL when passed one as a data source.
        // This entry point accepts files only, including documents supplied by Android providers.
        val files = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
        val sent =
            files.count { uri ->
                runCatching {
                    context.startForegroundService(
                        Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_IMPORT)
                            .setData(uri)
                            .putExtra(EXTRA_SPEECH_FILTER, speechFilter)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { android.util.Log.w("Echo", "event=import_start_failed", it) }.isSuccess
            }
        // The new card in the list is the answer; a line on top of it only repeated it. Only a file that cannot be read is said.
        if (sent == 0) Toast.makeText(context, R.string.import_failed_start, Toast.LENGTH_LONG).show()
        return sent > 0
    }
}
