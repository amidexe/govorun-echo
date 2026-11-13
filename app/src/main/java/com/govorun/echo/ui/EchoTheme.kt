package com.govorun.echo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.govorun.echo.R
import com.product.ui.ProductTheme
import com.product.ui.productTypography

private val Geist =
    FontFamily(
        Font(R.font.geist_variable, FontWeight.Normal),
        Font(R.font.geist_variable, FontWeight.Medium),
        Font(R.font.geist_variable, FontWeight.SemiBold),
        Font(R.font.geist_variable, FontWeight.Bold),
    )

/**
 * Geist is one variable file. Declared once for every weight, Android loads it once and draws every weight as regular: names
 * then read like the text under them. Each weight the type scale uses gets a family of its own, with its axis said outright.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun geistAt(weight: FontWeight) =
    FontFamily(Font(R.font.geist_variable, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))))

private val EchoWeightFamilies = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).associateWith(::geistAt)

/** A weight override must also select Geist's variable axis; fontWeight alone keeps the old fixed family. */
internal fun TextStyle.echoWeight(weight: FontWeight): TextStyle = copy(
    fontWeight = weight, fontFamily = EchoWeightFamilies[weight] ?: geistAt(weight),
)

private val EchoTypography =
    productTypography(Geist).let { t ->
        fun TextStyle.drawn() = fontWeight?.takeIf { it != FontWeight.Normal }?.let { copy(fontFamily = geistAt(it)) } ?: this
        t.copy(
            displayLarge = t.displayLarge.drawn(), displayMedium = t.displayMedium.drawn(), displaySmall = t.displaySmall.drawn(),
            headlineLarge = t.headlineLarge.drawn(), headlineMedium = t.headlineMedium.drawn(), headlineSmall = t.headlineSmall.drawn(),
            titleLarge = t.titleLarge.drawn(), titleMedium = t.titleMedium.drawn(), titleSmall = t.titleSmall.drawn(),
            bodySmall = t.bodySmall.drawn(), labelLarge = t.labelLarge.drawn(), labelMedium = t.labelMedium.drawn(), labelSmall = t.labelSmall.drawn(),
        )
    }

/** Same design system as Govorun: shared tokens, Geist type, system light/dark. */
@Composable
fun EchoTheme(
    themeMode: Int,
    dynamicColor: Boolean,
    updateSystemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            com.govorun.echo.AppSettings.THEME_LIGHT -> false
            com.govorun.echo.AppSettings.THEME_DARK -> true
            else -> isSystemInDarkTheme()
        }
    val view = LocalView.current
    if (updateSystemBars) SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    ProductTheme(darkTheme = dark, dynamicColor = dynamicColor, typography = EchoTypography, content = content)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
