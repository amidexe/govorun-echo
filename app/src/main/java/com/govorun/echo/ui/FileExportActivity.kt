package com.govorun.echo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*
import com.govorun.echo.AppSettings
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.R
import com.govorun.echo.backup.FolderExports
import com.product.ui.*

internal enum class FileExportMode { Notes, Markdown, Automatic, Manage }

class FileExportActivity : ComponentActivity() {
    private val model: FileExportModel by viewModels()
    private var picked: android.net.Uri? = null
    private var reauthorize = false
    private val choose = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> val expected = model.tree
        if (uri != null) {
            model.tree = uri
            picked = uri
            if (reauthorize && uri == expected) model.enable()
        }
        reauthorize = false }
    private val inspect = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.inspect(uri) }
    private val zip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> model.save(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = FileExportMode.entries.firstOrNull { it.name == intent.getStringExtra(MODE) } ?: FileExportMode.Notes
        model.load(mode)
        setContent { EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this)) { AppLockGate { Content(mode) } } }
    }

    @Composable
    private fun Content(mode: FileExportMode) {
        val automatic = mode == FileExportMode.Automatic
        val manage = mode == FileExportMode.Manage
        var scopePicker by rememberSaveable { mutableStateOf(false) }
        var formatPicker by rememberSaveable { mutableStateOf(false) }
        val revision by FolderExports.changes.collectAsState()
        val config = remember(revision) { FolderExports.config(this) }
        val remembered = remember(revision) { FolderExports.lastTree(this) }
        val busy = model.busy
        val noteIds = model.selected
        val tags = model.tags
        val tree = model.tree
        val options = model.options
        val scope = if (automatic) tags?.sorted()?.joinToString(", ")?.ifBlank { null }
            ?: stringResource(if (tags == null) R.string.computer_all else R.string.folder_none)
            else if (noteIds.isEmpty()) stringResource(R.string.folder_none) else stringResource(R.string.folder_selection_count, noteIds.size)
        val format = stringResource(if (options.markdown) R.string.folder_markdown else R.string.folder_plain)
        val label = if (automatic || manage) folderLabel(tree) else null
        BackHandler(enabled = busy) { }
        ProductScaffold(title = stringResource(when (mode) {
            FileExportMode.Manage -> R.string.folder_manage
            FileExportMode.Automatic -> R.string.folder_auto_title
            FileExportMode.Markdown -> R.string.folder_once_markdown
            FileExportMode.Notes -> R.string.settings_export_all
        }),
            onBack = { if (!busy) finish() }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(productScreenPadding()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (manage) {
                    SettingsInfoRow(PhosphorIcons.Regular.FolderOpen, label ?: stringResource(R.string.folder_manage),
                        stringResource(if (config == null) R.string.folder_paused else R.string.folder_enabled))
                } else {
                    val rows: @Composable () -> Unit = {
                        SettingsLinkRow(PhosphorIcons.Regular.CheckCircle, stringResource(R.string.folder_scope), scope,
                            enabled = !busy, onClick = { scopePicker = true })
                        ProductDivider()
                        if (automatic) {
                            SettingsLinkRow(PhosphorIcons.Regular.FileText, stringResource(R.string.folder_format), format,
                                enabled = !busy, onClick = { formatPicker = true })
                            ProductDivider()
                        }
                        SettingsSwitchRow(stringResource(R.string.folder_audio), stringResource(R.string.folder_audio_text),
                            options.audio, { model.options = options.copy(audio = it) }, !busy, PhosphorIcons.Regular.SpeakerHigh)
                        if (automatic) {
                            ProductDivider()
                            SettingsLinkRow(PhosphorIcons.Regular.FolderOpen, stringResource(R.string.folder_destination),
                                label ?: stringResource(R.string.folder_choose_location), enabled = !busy, onClick = { choose.launch(tree) })
                        }
                    }
                    if (automatic) {
                        HelpSection(stringResource(R.string.folder_setup), R.string.folder_help_title, R.string.folder_auto_help) { rows() }
                        Text(stringResource(R.string.folder_explain), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        SettingsSection("", showTitle = false) { rows() }
                        Text(stringResource(if (mode == FileExportMode.Markdown) R.string.folder_once_markdown_summary else R.string.folder_once_summary), style = MaterialTheme.typography.bodyMedium)
                    }
                    val networkRevision by NetworkPolicy.changes.collectAsState()
                    val refusal = remember(tree, networkRevision) { tree?.let { NetworkPolicy.folderRefusal(it.authority) } }
                    val dirty = config == null || config.tree != tree || config.tags != tags || config.options != options
                    if (automatic && refusal != null) NetworkNotice(refusal)
                    val ready = if (automatic) tree != null && (tags == null || tags.isNotEmpty()) && dirty && refusal == null else noteIds.isNotEmpty()
                    if (!automatic || dirty) Button(enabled = !busy && ready, modifier = Modifier.fillMaxWidth(), colors = productButtonColors(), onClick = {
                        if (automatic && tree != null) {
                            val granted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
                            if (granted || picked == tree) model.enable() else { reauthorize = true; choose.launch(tree) }
                        } else if (model.chooseZip()) zip.launch("govorun-echo-notes.zip")
                    }) { Text(stringResource(if (!automatic) R.string.folder_once_save else if (config == null) R.string.folder_start else R.string.folder_apply)) }
                }
                if (automatic || manage) SettingsSection(stringResource(R.string.folder_review)) {
                    if (config != null) {
                        SettingsLinkRow(PhosphorIcons.Regular.XCircle, stringResource(R.string.folder_stop), enabled = !busy, onClick = { picked = null; model.stop() })
                        ProductDivider()
                    }
                    SettingsLinkRow(PhosphorIcons.Regular.Trash, stringResource(R.string.folder_cleanup),
                        stringResource(if (config == null) R.string.folder_cleanup_summary else R.string.folder_cleanup_stop_first),
                        enabled = !busy && config == null, onClick = { inspect.launch(remembered ?: tree) })
                    if (remembered != null && config == null) {
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.FolderSimple, stringResource(R.string.folder_forget), enabled = !busy, onClick = model::forget)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.message.isNotBlank()) Text(model.message, style = MaterialTheme.typography.bodyMedium)
                if (automatic && config != null) {
                    val error = remember(revision) { FolderExports.lastError(this@FileExportActivity) }
                    if (error != null) Text(stringResource(when (error) {
                        "copy_conflict" -> R.string.backup_conflict
                        "no_access" -> R.string.backup_no_access
                        else -> R.string.backup_write_failed
                    }), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (scopePicker) {
            if (automatic) ScopeTagPicker((model.notes.flatMap { it.tags } + AppSettings.tags(this)).distinct(), tags,
                R.string.folder_select_tags, { model.tags = it; scopePicker = false }, { scopePicker = false })
            else ExportNotePicker(model.notes, noteIds, { model.selected = it; scopePicker = false }, { scopePicker = false })
        }
        if (formatPicker) {
            val plain = stringResource(R.string.folder_plain)
            val markdown = stringResource(R.string.folder_markdown)
            ProductChoiceSheet(stringResource(R.string.folder_format), options.markdown, listOf(false, true),
                { if (it) markdown else plain }, { model.options = options.copy(markdown = it) }, { formatPicker = false })
        }
        model.cleanup?.let { plan ->
            var includeIndex by remember(plan) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { model.cleanup = null }, title = { Text(stringResource(R.string.folder_cleanup_title)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.folder_cleanup_preview, plan.files.size, plan.preserved))
                    if (plan.legacyIndex != null) SettingsSwitchRow(stringResource(R.string.folder_cleanup_index),
                        stringResource(R.string.folder_cleanup_index_info), includeIndex, { includeIndex = it })
                } },
                confirmButton = { TextButton(enabled = plan.files.isNotEmpty() || includeIndex, onClick = { model.remove(includeIndex) }) { Text(stringResource(R.string.folder_delete_action)) } },
                dismissButton = { TextButton(onClick = { model.cleanup = null }) { Text(stringResource(R.string.action_cancel)) } })
        }
    }

    @Composable
    private fun folderLabel(tree: android.net.Uri?): String? {
        val label by produceState<String?>(null, tree) {
            value = tree?.let { uri ->
                val id = DocumentsContract.getTreeDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) {
                    id.removePrefix("primary:").replace("/", " / ").ifBlank { getString(R.string.folder_device) }
                } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { NetworkPolicy.withFolderAccess(uri.authority) { checkAccess ->
                        checkAccess()
                        contentResolver.query(FolderExports.root(uri), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                    } }.getOrNull() ?: uri.authority
                }
            }
        }
        return label
    }

    companion object {
        private const val MODE = "export_mode"
        internal fun intent(context: Context, mode: FileExportMode) = Intent(context, FileExportActivity::class.java).putExtra(MODE, mode.name)
    }
}
