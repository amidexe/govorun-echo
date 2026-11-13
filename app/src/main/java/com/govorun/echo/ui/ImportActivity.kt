package com.govorun.echo.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.content.Context
import com.govorun.echo.record.FileImport
import com.govorun.echo.record.SavedNotice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.AppEntry

/** Receives "Share" (one file or several) and "Open with" for audio or video, hands them to the service and shows the app. */
class ImportActivity : ComponentActivity() {
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
        if (uris.isEmpty()) { finish(); return }
        setContent {
            com.govorun.echo.ui.EchoTheme(
                com.govorun.echo.AppSettings.themeMode(this),
                com.govorun.echo.AppSettings.dynamicColors(this),
            ) {
                var supported by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    supported = com.govorun.echo.recognition.RecognitionClient(this@ImportActivity).supportsFileSpeechFilter()
                    if (supported == false) importFiles(uris, false)
                }
                if (supported == true) ImportModeSheet(
                    onSelected = { importFiles(uris, it) },
                    onDismissRequest = { finish() },
                )
            }
        }
    }

    private fun importFiles(uris: List<Uri>, fast: Boolean) {
        if (FileImport.start(this, uris, fast)) {
            startActivity(AppEntry.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    companion object {
    /** From «Добавить файлы»: the picked files stay readable for the minutes their decoding may take. */
    fun picked(
        context: Context,
        uris: List<Uri>,
    ): Boolean {
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isEmpty()) return false
        return runCatching {
            context.startActivity(Intent(context, ImportActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply {
                    clipData = android.content.ClipData.newRawUri("", uris.first()).also { data ->
                        uris.drop(1).forEach { data.addItem(android.content.ClipData.Item(it)) }
                    }
                })
        }.isSuccess
    }

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

@Composable
internal fun ImportModeSheet(onSelected: (Boolean) -> Unit, onDismissRequest: () -> Unit) {
    val full = stringResource(R.string.import_recognition_full)
    val fast = stringResource(R.string.import_recognition_fast)
    val fullDescription = stringResource(R.string.import_recognition_full_description)
    val fastDescription = stringResource(R.string.import_recognition_fast_description)
    com.product.ui.ProductChoiceSheet(
        title = stringResource(R.string.import_recognition_mode),
        selected = false,
        options = listOf(false, true),
        optionLabel = { if (it) fast else full },
        optionDescription = { if (it) fastDescription else fullDescription },
        onSelected = onSelected,
        onDismissRequest = onDismissRequest,
    )
}
