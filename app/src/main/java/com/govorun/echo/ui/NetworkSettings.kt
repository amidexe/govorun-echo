package com.govorun.echo.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Airplane
import com.govorun.echo.R
import com.govorun.echo.NetworkPolicy
import com.product.ui.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun NetworkSettings() {
    val state by NetworkPolicy.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsSection(stringResource(R.string.network_title)) {
        SwitchRow(PhosphorIcons.Regular.Airplane, R.string.network_roaming, R.string.network_roaming_hint, state.noRoaming) {
            scope.launch(Dispatchers.IO) {
                if (!NetworkPolicy.setNoRoaming(it)) android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, R.string.network_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
