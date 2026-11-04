package com.govorun.echo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.adamglin.phosphoricons.regular.CloudSlash
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.govorun.echo.NetworkPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.Desktop
import com.adamglin.phosphoricons.regular.FileAudio
import com.adamglin.phosphoricons.regular.NotePencil
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Tray
import com.adamglin.phosphoricons.regular.Star
import com.govorun.echo.R

/** Quiet navigation groups, with Echo’s own typography, icons and selection colours. */
@Composable
internal fun EchoDrawer(
    tab: NotesTab,
    onFavorites: () -> Unit,
    onAll: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onStats: () -> Unit,
    computerOpen: Boolean,
    onComputer: () -> Unit,
    onNewText: () -> Unit,
    onAddFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    var confirmOffline by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val network by NetworkPolicy.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val offline: (Boolean) -> Unit = { value -> scope.launch(Dispatchers.IO) {
        if (!NetworkPolicy.setOffline(value)) android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, R.string.network_save_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }; Unit }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, modifier = Modifier.width(300.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 16.dp).semantics { heading() },
                )
                DrawerItem(PhosphorIcons.Regular.Tray, stringResource(R.string.tab_all), selected = tab == NotesTab.All, onClick = onAll)
                DrawerItem(PhosphorIcons.Regular.Star, stringResource(R.string.favorites_title), selected = tab == NotesTab.Favorites, onClick = onFavorites)
                DrawerItem(PhosphorIcons.Regular.Archive, stringResource(R.string.tab_archive), selected = tab == NotesTab.Archive, onClick = onArchive)
                DrawerItem(PhosphorIcons.Regular.Trash, stringResource(R.string.screen_trash), selected = false, onClick = onTrash)
                DrawerDivider()
                DrawerItem(PhosphorIcons.Regular.NotePencil, stringResource(R.string.drawer_new_text), selected = false, onClick = onNewText)
                DrawerItem(PhosphorIcons.Regular.FileAudio, stringResource(R.string.drawer_add_files), selected = false, onClick = onAddFiles)
                DrawerDivider()
                DrawerItem(PhosphorIcons.Regular.ChartBar, stringResource(R.string.stats_title), selected = false, onClick = onStats)
            }
            DrawerDivider()
            DrawerItem(PhosphorIcons.Regular.Desktop, stringResource(R.string.computer_entry), selected = false, onClick = onComputer, activeDot = computerOpen)
            DrawerItem(PhosphorIcons.Regular.CloudSlash, stringResource(R.string.network_offline), false,
                onClick = { if (network.offline) offline(false) else confirmOffline = true }, checked = network.offline,
                trailing = { androidx.compose.material3.Switch(checked = network.offline, onCheckedChange = null) })
            DrawerItem(PhosphorIcons.Regular.Gear, stringResource(R.string.screen_settings), selected = false, onClick = onSettings)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmOffline) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmOffline = false },
        title = { Text(stringResource(R.string.network_offline_confirm_title)) },
        text = { Text(stringResource(R.string.network_offline_confirm_text)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmOffline = false; offline(true) }) { Text(stringResource(R.string.network_enable)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmOffline = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

/** One row of the menu: a quiet outline icon and an ordinary label; the open place lit in Echo's colours. */
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    note: String? = null,
    activeDot: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    checked: Boolean? = null,
) {
    val activeDescription = if (activeDot) stringResource(R.string.computer_mode_enabled) else ""
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().semantics { if (activeDot) stateDescription = activeDescription }.then(if (checked != null) Modifier.toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { onClick() }) else Modifier.clickable(onClick = onClick)).height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            // The app's own body size, as the rest of Echo: the menu is a list of places, not a headline.
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // A state beside the place, as «открыто» while Echo is open on a computer.
            if (activeDot) Canvas(Modifier.size(6.dp)) { drawCircle(com.product.ui.ProductTokens.Color.Success) }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            trailing?.invoke()
        }
    }
}
