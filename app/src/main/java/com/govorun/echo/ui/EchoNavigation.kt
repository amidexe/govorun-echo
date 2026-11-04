package com.govorun.echo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.launch

/** The app menu and its existing navigation destinations. */
@Composable
internal fun EchoNavigation(
    notes: List<NoteStore.Entry>,
    tab: NotesTab,
    actions: NotesActions,
    content: @Composable (DrawerState, () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var stats by remember { mutableStateOf(false) }
    val computerOpen by com.govorun.echo.web.ComputerSession.connected.collectAsState()
    fun navigate(action: () -> Unit) { scope.launch { drawer.close(); action() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        EchoDrawer(
            tab = tab,
            onFavorites = { navigate { actions.onTab(NotesTab.Favorites) } },
            onAll = { navigate { actions.onTagFilter(null); actions.onTab(NotesTab.All) } },
            onArchive = { navigate { actions.onTagFilter(null); actions.onTab(NotesTab.Archive) } },
            onTrash = { navigate(actions.onTrash) },
            onStats = { navigate { stats = true } },
            computerOpen = computerOpen,
            onComputer = { navigate { ComputerRequest.asked = ComputerRequest.Asked() } },
            onNewText = { navigate(actions.onNewText) },
            onAddFiles = { navigate(actions.onAddFiles) },
            onSettings = { navigate(actions.onSettings) },
        )
    }) {
        Box { content(drawer) { keyboard?.hide(); scope.launch { drawer.open() } }; if (stats) StatsScreen(notes) { stats = false } }
    }
    // The menu owns Back while open, including over editors with their own Back handlers.
    if (drawer.isOpen) androidx.activity.compose.BackHandler { scope.launch { drawer.close() } }
}
