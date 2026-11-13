package com.govorun.echo.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Palette
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.widget.EchoWidget
import com.govorun.echo.widget.WidgetAppearance
import com.govorun.echo.widget.WidgetAction
import com.product.ui.*
import kotlin.math.roundToInt

/** The launcher's standard widget configuration and reconfiguration entry. */
class WidgetAppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider?.takeIf { it.packageName == packageName }
        val action = WidgetAction.forProvider(provider?.className)
        if (action == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val initial = WidgetAppearance.load(this, id)
        setContent {
            EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this)) {
                WidgetAppearanceScreen(initial, action, ::finish) { choice ->
                    choice.save(this, id)
                    EchoWidget.update(this, manager, id, action)
                    setResult(RESULT_OK, result)
                    finish()
                }
            }
        }
    }
}

@Composable
private fun WidgetAppearanceScreen(initial: WidgetAppearance, action: WidgetAction, onBack: () -> Unit, onSave: (WidgetAppearance) -> Unit) {
    var size by rememberSaveable { mutableIntStateOf(initial.sizeDp) }
    var opacity by rememberSaveable { mutableIntStateOf(initial.backgroundOpacity) }
    var white by rememberSaveable { mutableStateOf(initial.whiteBird) }
    var chooseBird by rememberSaveable { mutableStateOf(false) }
    val appearance = WidgetAppearance(size, opacity, white)
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.dp.roundToPx() }.coerceAtLeast(1)
    val preview = remember(appearance, sizePx, action) { appearance.icon(context, sizePx, action.glyph).asImageBitmap() }
    val birdLabel = { light: Boolean -> context.getString(if (light) R.string.widget_bird_white else R.string.widget_bird_dark) }
    val colorLabel = stringResource(if (action == WidgetAction.RECORD) R.string.widget_bird_color else R.string.widget_icon_color)

    ProductScaffold(title = stringResource(action.title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(productScreenPadding()),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Section),
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(ProductTokens.Radius.Card),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) {
                    Image(preview, stringResource(R.string.widget_preview), Modifier.size(size.dp))
                }
            }
            SettingsSection("", showTitle = false) {
                Column(Modifier.padding(horizontal = ProductTokens.Spacing.Section, vertical = ProductTokens.Spacing.Lg)) {
                    val sizeLabel = stringResource(R.string.widget_icon_size)
                    Text(sizeLabel, style = MaterialTheme.typography.titleMedium)
                    Slider(size.toFloat(), { size = it.roundToInt() },
                        Modifier.semantics { contentDescription = sizeLabel },
                        valueRange = WidgetAppearance.MIN_SIZE.toFloat()..WidgetAppearance.MAX_SIZE.toFloat())
                    val opacityLabel = stringResource(R.string.widget_background_opacity)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(opacityLabel, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.widget_opacity_value, opacity), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(opacity.toFloat(), { opacity = it.roundToInt() },
                        Modifier.semantics { contentDescription = opacityLabel }, valueRange = 0f..100f)
                }
                ProductDivider()
                SettingsLinkRow(PhosphorIcons.Regular.Palette, colorLabel,
                    value = birdLabel(white), onClick = { chooseBird = true })
            }
            Button(onClick = { onSave(appearance) }, modifier = Modifier.fillMaxWidth(), colors = productButtonColors()) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
    if (chooseBird) ProductChoiceSheet(
        title = colorLabel, selected = white,
        options = listOf(false, true), optionLabel = birdLabel,
        onSelected = { white = it; chooseBird = false }, onDismissRequest = { chooseBird = false },
    )
}
