package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Code
import com.adamglin.phosphoricons.regular.Gavel
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.User
import com.govorun.echo.BuildConfig
import com.govorun.echo.R
import com.govorun.echo.update.AppUpdates
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.productScreenPadding

internal val appVersionLabel =
    "${BuildConfig.VERSION_NAME.removeSuffix("-debug")} (${BuildConfig.VERSION_CODE})"

@Composable
internal fun AboutScreen(
    install: InstallInfo,
    experimentsUnlocked: Boolean,
    onBack: () -> Unit,
    onUnlockExperiments: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var versionTaps by rememberSaveable { mutableIntStateOf(0) }
    ProductScaffold(title = stringResource(R.string.about_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Screen),
        ) {
            item {
                SettingsSection("", showTitle = false) {
                    val label = stringResource(R.string.settings_version_title)
                    if (experimentsUnlocked) {
                        SettingsInfoRow(PhosphorIcons.Regular.Tag, label, appVersionLabel)
                    } else {
                        SettingsLinkRow(PhosphorIcons.Regular.Tag, label, appVersionLabel) {
                            if (++versionTaps == UNLOCK_TAPS) onUnlockExperiments()
                        }
                    }
                    if (AppUpdates.enabled) UpdateSettingsRow()
                }
            }
            item {
                SettingsSection(stringResource(R.string.about_author_section)) {
                    SettingsInfoRow(PhosphorIcons.Regular.User, stringResource(R.string.about_author_title),
                        stringResource(R.string.about_author_body))
                    SettingsInfoRow(PhosphorIcons.Regular.Code, stringResource(R.string.about_open_projects_title),
                        stringResource(R.string.about_open_projects_body))
                }
            }
            item {
                SettingsSection(stringResource(R.string.about_links_section)) {
                    SettingsLinkRow(PhosphorIcons.Regular.Globe, stringResource(R.string.about_site_title),
                        install.siteUrl.removePrefix("https://").trimEnd('/'), onClick = { onOpenUrl(install.siteUrl) })
                    SettingsLinkRow(PhosphorIcons.Regular.Gavel, stringResource(R.string.licenses_screen),
                        onClick = onOpenLicenses)
                    SettingsLinkRow(PhosphorIcons.Regular.ShieldCheck, stringResource(R.string.about_legal_title),
                        stringResource(R.string.about_legal_body), onClick = { onOpenUrl(InstallInfo.LEGAL_URL) })
                }
            }
        }
    }
}
