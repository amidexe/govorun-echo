package com.govorun.echo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Certificate
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.LockSimple
import com.adamglin.phosphoricons.regular.Scales
import com.adamglin.phosphoricons.regular.Star
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.User
import com.govorun.echo.R
import com.govorun.echo.update.AppUpdates
import com.product.ui.FeedCard
import com.product.ui.ProductDivider
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.productScreenPadding

/** "About": who made Echo, what it promises, where to leave a review, the documents and the version. Laid out like Govorun's own screen. */
@Composable
internal fun AboutScreen(
    versionName: String,
    install: InstallInfo,
    onBack: () -> Unit,
    onVersionTap: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ProductScaffold(title = stringResource(R.string.about_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            item {
                FeedCard {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(ProductTokens.Spacing.Section),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm),
                    ) {
                        Image(painterResource(R.drawable.ic_echo_art), contentDescription = null, modifier = Modifier.size(96.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.about_version_fmt, versionName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_about_title)) {
                    SettingsInfoRow(PhosphorIcons.Regular.LockSimple, stringResource(R.string.about_row_mine_title), stringResource(R.string.about_row_mine_body))
                    ProductDivider()
                    SettingsInfoRow(PhosphorIcons.Regular.Info, stringResource(R.string.settings_auto_title), stringResource(R.string.settings_auto_text))
                    ProductDivider()
                    SettingsInfoRow(PhosphorIcons.Regular.Scales, stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_text))
                    ProductDivider()
                    SettingsInfoRow(PhosphorIcons.Regular.Star, stringResource(R.string.fair_settings_title), stringResource(R.string.smart_enable_text))
                }
            }
            item {
                SettingsSection(stringResource(R.string.about_author_section)) {
                    SettingsInfoRow(PhosphorIcons.Regular.User, stringResource(R.string.about_author_title), stringResource(R.string.about_author_body))
                    ProductDivider()
                    SettingsInfoRow(PhosphorIcons.Regular.Heart, stringResource(R.string.about_thanks_title), stringResource(R.string.about_thanks_body))
                    if (install.reviewUrl.isNotEmpty()) {
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.Star, stringResource(R.string.about_review_title), stringResource(R.string.about_review_body), onClick = { onOpenUrl(install.reviewUrl) })
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.about_links_section)) {
                    SettingsLinkRow(PhosphorIcons.Regular.Globe, stringResource(R.string.about_site_title), install.siteUrl.removePrefix("https://").trimEnd('/'), onClick = { onOpenUrl(install.siteUrl) })
                    ProductDivider()
                    SettingsLinkRow(PhosphorIcons.Regular.Scales, stringResource(R.string.about_legal_title), stringResource(R.string.about_legal_body), onClick = { onOpenUrl(InstallInfo.LEGAL_URL) })
                    ProductDivider()
                    SettingsLinkRow(PhosphorIcons.Regular.Certificate, stringResource(R.string.licenses_title), onClick = onOpenLicenses)
                    ProductDivider()
                    if (AppUpdates.enabled) {
                        UpdateSettingsRow()
                        ProductDivider()
                    }
                    Surface(onClick = onVersionTap, color = Color.Transparent) {
                        SettingsInfoRow(PhosphorIcons.Regular.Tag, stringResource(R.string.settings_version_title), stringResource(R.string.settings_version_value, versionName))
                    }
                }
            }
        }
    }
}
