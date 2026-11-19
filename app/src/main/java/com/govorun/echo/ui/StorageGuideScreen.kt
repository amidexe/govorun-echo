package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.Devices
import com.govorun.echo.R
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import com.product.ui.productScreenPadding

@Composable
internal fun StorageGuideScreen(onBack: () -> Unit) {
    val theme = MaterialTheme.colorScheme
    ProductScaffold(stringResource(R.string.storage_guide_title), onBack = onBack) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(productScreenPadding())) {
            Surface(shape = RoundedCornerShape(ProductTokens.Radius.Card), color = theme.primaryContainer) {
                GuideEntry(PhosphorIcons.Regular.DeviceMobile, R.string.storage_guide_source, R.string.storage_guide_source_body)
            }
            StoragePath(PhosphorIcons.Regular.Devices, R.string.storage_guide_android, R.string.storage_guide_android_body)
            StoragePath(PhosphorIcons.Regular.Cloud, R.string.storage_guide_cloud, R.string.storage_guide_cloud_body)
            StoragePath(PhosphorIcons.Regular.Archive, R.string.storage_guide_archive, R.string.storage_guide_archive_body, last = true)
            Column(Modifier.padding(horizontal = ProductTokens.Spacing.Lg, vertical = ProductTokens.Spacing.Screen),
                verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
                Text(stringResource(R.string.storage_guide_external), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.storage_guide_external_body), style = MaterialTheme.typography.bodyMedium, color = theme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StoragePath(icon: ImageVector, title: Int, body: Int, last: Boolean = false) {
    val line = MaterialTheme.colorScheme.outline
    val gutter = 32.dp
    // Every destination branches from the phone. The line follows the row's measured height at any font scale.
    Column(Modifier.fillMaxWidth().drawBehind {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val start = if (rtl) size.width - 15.dp.toPx() else 15.dp.toPx()
        val end = if (rtl) size.width - gutter.toPx() else gutter.toPx()
        val junction = 30.dp.toPx().coerceAtMost(size.height)
        drawLine(line, Offset(start, 0f), Offset(start, if (last) junction else size.height), 1.dp.toPx())
        drawLine(line, Offset(start, junction), Offset(end, junction), 1.dp.toPx())
    }.padding(start = gutter, top = ProductTokens.Spacing.Lg)) {
        Surface(shape = RoundedCornerShape(ProductTokens.Radius.Card), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            GuideEntry(icon, title, body)
        }
    }
}

@Composable
private fun GuideEntry(icon: ImageVector, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth().padding(ProductTokens.Spacing.RowContent),
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxl)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ProductTokens.Size.GroupedRowIcon))
        Column(verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
            Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
