package com.govorun.echo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.UploadSimple
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.backup.ArchiveRestore
import com.govorun.echo.backup.UserTransfer
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.ProductNavigationButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A regular settings screen. Native picker providers supply the file; Echo never requires a cloud account. */
class TransferActivity:ComponentActivity() {
    private val model:TransferViewModel by viewModels()
    private val save=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->if(uri!=null)model.save(uri)}
    private val open=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null)model.open(uri)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EchoTheme(AppSettings.themeMode(this),AppSettings.dynamicColors(this)) { AppLockGate { Screen() } } }
    }
    private fun back() {
        val state=model.state.value
        if(state.busy)return
        model.discard()
        if(state.restored)startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Screen() {
        val state by model.state.collectAsState()
        val busy=state.busy;val message=state.message;val pending=state.pending;val settings=state.settings
        BackHandler {back()}
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.transfer_title)) },
                    navigationIcon = {
                        ProductNavigationButton(
                            PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.action_back), ::back, enabled = !busy,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.transfer_description),style=MaterialTheme.typography.bodyMedium)
                SettingsSection(stringResource(R.string.transfer_full)) {
                    SettingsLinkRow(PhosphorIcons.Regular.UploadSimple,stringResource(R.string.transfer_save),onClick={if(!busy)save.launch("govorun-echo-${java.time.LocalDate.now()}.echo.zip")})
                    SettingsLinkRow(PhosphorIcons.Regular.DownloadSimple,stringResource(R.string.transfer_open),onClick={if(!busy)open.launch(arrayOf("application/zip","application/octet-stream","*/*"))})
                }
                if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.transfer_working),style=MaterialTheme.typography.bodyMedium)}
                if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodyMedium)
            }
        }
        pending?.let { value ->
            AlertDialog(onDismissRequest={if(!busy){model.discard()}},title={Text(stringResource(R.string.transfer_preview))},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(getString(R.string.transfer_preview_counts,value.plan.count(ArchiveRestore.Kind.New),value.plan.count(ArchiveRestore.Kind.Enrich),value.plan.count(ArchiveRestore.Kind.Same),value.plan.count(ArchiveRestore.Kind.Conflict),value.plan.count(ArchiveRestore.Kind.Damaged)))
                    Text(stringResource(R.string.transfer_preserve))
                    if(value.plan.count(ArchiveRestore.Kind.Conflict)+value.plan.count(ArchiveRestore.Kind.Damaged)>0) Text(value.plan.units.filter{(it.kind==ArchiveRestore.Kind.Conflict || it.kind==ArchiveRestore.Kind.Damaged)}.take(20).joinToString("\n"){it.label()})
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=settings,onCheckedChange={model.settings(it)},enabled=!busy);Text(stringResource(R.string.transfer_settings))}
                }
            },confirmButton={TextButton(enabled=!busy,onClick={
                model.restore()
            }){Text(stringResource(R.string.transfer_restore))}},dismissButton={TextButton(enabled=!busy,onClick={model.discard()}){Text(stringResource(R.string.action_cancel))}})
        }
    }
}
