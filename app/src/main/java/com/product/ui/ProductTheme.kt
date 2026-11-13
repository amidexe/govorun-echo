package com.product.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = ProductTokens.Color.BrandAccent,
    onPrimary = Color.White,
    primaryFixed = ProductTokens.Color.BrandAccent,
    onPrimaryFixed = Color.White,
    primaryFixedDim = Color(0xFF58494A),
    onPrimaryFixedVariant = Color.White,
    primaryContainer = Color(0xFFEFDEE1),
    onPrimaryContainer = Color(0xFF26191C),
    secondary = ProductTokens.Color.BrandAccent,
    onSecondary = ProductTokens.Color.LightSurface,
    secondaryContainer = Color(0xFFEBDFDF),
    onSecondaryContainer = ProductTokens.Color.LightText,
    secondaryFixed = Color(0xFFEBDFDF),
    secondaryFixedDim = Color(0xFFD0C4C4),
    onSecondaryFixed = ProductTokens.Color.LightText,
    onSecondaryFixedVariant = Color(0xFF514447),
    tertiary = Color(0xFF705B56),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5DED8),
    onTertiaryContainer = Color(0xFF291813),
    tertiaryFixed = Color(0xFFF5DED8),
    tertiaryFixedDim = Color(0xFFD9C2BC),
    onTertiaryFixed = Color(0xFF291813),
    onTertiaryFixedVariant = Color(0xFF58423C),
    background = ProductTokens.Color.LightBackground,
    onBackground = ProductTokens.Color.LightText,
    surface = ProductTokens.Color.LightSurface,
    onSurface = ProductTokens.Color.LightText,
    surfaceVariant = Color(0xFFEBDFDF),
    surfaceTint = ProductTokens.Color.BrandAccent,
    surfaceDim = Color(0xFFE0D6D5),
    surfaceBright = ProductTokens.Color.LightSurface,
    onSurfaceVariant = ProductTokens.Color.LightMutedText,
    surfaceContainerLowest = ProductTokens.Color.LightSurface,
    surfaceContainerLow = ProductTokens.Color.LightMutedSurface,
    surfaceContainer = Color(0xFFFCF0EF),
    surfaceContainerHigh = Color(0xFFF6ECEB),
    surfaceContainerHighest = Color(0xFFEBDFDF),
    outline = Color(0xFF9B8E90),
    outlineVariant = Color(0xFFBDAFB1),
    inverseSurface = ProductTokens.Color.DarkSurface,
    inverseOnSurface = ProductTokens.Color.DarkText,
    inversePrimary = ProductTokens.Color.BrandAccentDark,
    error = Color(0xFFE34A55),
    errorContainer = Color(0xFFFFE2E2),
    onErrorContainer = Color(0xFF4C0608),
)

private val DarkColors = darkColorScheme(
    // The owner's reference palette: dusty rose controls on warm charcoal surfaces.
    primary = ProductTokens.Color.BrandAccentDark,
    onPrimary = Color(0xFF382D30),
    // Product filled controls use these roles in both static and wallpaper-derived palettes.
    primaryFixed = ProductTokens.Color.BrandAccentDark,
    onPrimaryFixed = Color(0xFF382D30),
    primaryFixedDim = Color(0xFFBBA6A9),
    onPrimaryFixedVariant = Color(0xFF382D30),
    primaryContainer = Color(0xFF514145),
    onPrimaryContainer = Color(0xFFEFDEE1),
    secondary = ProductTokens.Color.BrandAccentDark,
    onSecondary = Color(0xFF382D30),
    secondaryContainer = Color(0xFF504447),
    onSecondaryContainer = ProductTokens.Color.DarkText,
    secondaryFixed = Color(0xFFEBDFDF),
    secondaryFixedDim = Color(0xFFD0C4C4),
    onSecondaryFixed = ProductTokens.Color.LightText,
    onSecondaryFixedVariant = Color(0xFF514447),
    tertiary = Color(0xFFDCC1B9),
    onTertiary = Color(0xFF3E2D27),
    tertiaryContainer = Color(0xFF58423C),
    onTertiaryContainer = Color(0xFFF5DED8),
    tertiaryFixed = Color(0xFFF5DED8),
    tertiaryFixedDim = Color(0xFFD9C2BC),
    onTertiaryFixed = Color(0xFF291813),
    onTertiaryFixedVariant = Color(0xFF58423C),
    background = ProductTokens.Color.DarkBackground,
    onBackground = ProductTokens.Color.DarkText,
    surface = ProductTokens.Color.DarkSurface,
    onSurface = ProductTokens.Color.DarkText,
    surfaceVariant = ProductTokens.Color.DarkMutedSurface,
    surfaceTint = ProductTokens.Color.BrandAccentDark,
    surfaceDim = ProductTokens.Color.DarkBackground,
    surfaceBright = Color(0xFF3D3638),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = ProductTokens.Color.DarkSurface,
    surfaceContainer = ProductTokens.Color.DarkSurface,
    surfaceContainerHigh = ProductTokens.Color.DarkMutedSurface,
    surfaceContainerHighest = Color(0xFF332D2F),
    onSurfaceVariant = ProductTokens.Color.DarkMutedText,
    outline = Color(0xFF8E8083),
    outlineVariant = Color(0xFF51464A),
    inverseSurface = ProductTokens.Color.LightMutedSurface,
    inverseOnSurface = ProductTokens.Color.LightText,
    inversePrimary = ProductTokens.Color.BrandAccent,
    error = Color(0xFFFF6A70),
    errorContainer = Color(0xFF651114),
    onErrorContainer = Color(0xFFFFE2E2),
)

private val ProductShapes = Shapes(
    extraSmall = RoundedCornerShape(ProductTokens.Radius.Xs),
    small = RoundedCornerShape(ProductTokens.Radius.Sm),
    medium = RoundedCornerShape(ProductTokens.Radius.Md),
    large = RoundedCornerShape(ProductTokens.Radius.Card),
    extraLarge = RoundedCornerShape(ProductTokens.Radius.Xl),
)

@Composable
fun ProductTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    typography: Typography,
    content: @Composable () -> Unit,
) {
    val colors = productColorScheme(LocalContext.current, darkTheme, dynamicColor)
    MaterialTheme(colors, ProductShapes, typography, content)
}

internal fun productColorScheme(context: Context, dark: Boolean, dynamic: Boolean): ColorScheme {
    val usesDynamic = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        usesDynamic && dark -> readableDynamic(dynamicDarkColorScheme(context), true)
        usesDynamic -> readableDynamic(dynamicLightColorScheme(context), false)
        dark -> DarkColors
        else -> LightColors
    }
}

/** Filled controls follow the current palette's primary and its contrasting foreground. */
@Composable
fun productButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryFixed, contentColor = MaterialTheme.colorScheme.onPrimaryFixed)

/** Geist roles for the product chrome and note content. */
fun productTypography(fontFamily: FontFamily): Typography {
    val screenTitle = productTextStyle(fontFamily, FontWeight.Normal, 17.sp, 22.sp)
    val title = productTextStyle(fontFamily, FontWeight.SemiBold, 15.sp, 20.sp)
    val body = productTextStyle(fontFamily, FontWeight.Normal, 14.sp, 20.sp)
    val caption = productTextStyle(fontFamily, FontWeight.Medium, 11.sp, 14.sp)
    val action = productTextStyle(fontFamily, FontWeight.Normal, 13.sp, 18.sp)
    val metric = productTextStyle(fontFamily, FontWeight.SemiBold, 22.sp, 28.sp)
    // Material controls and app Text calls resolve through the same small role set.
    return Typography(
        displayLarge = metric,
        displayMedium = metric,
        displaySmall = metric,
        headlineLarge = metric,
        headlineMedium = metric,
        headlineSmall = title,
        titleLarge = screenTitle,
        titleMedium = title,
        titleSmall = title,
        bodyLarge = productTextStyle(fontFamily, FontWeight.Normal, 15.sp, 21.sp),
        bodyMedium = body,
        bodySmall = action,
        labelLarge = action,
        labelMedium = caption,
        labelSmall = caption,
    )
}

private fun productTextStyle(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    height: TextUnit,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSynthesis = FontSynthesis.None,
    fontSize = size,
    lineHeight = height,
    fontFeatureSettings = "\"cv11\", \"ss01\", \"ss03\"",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private fun readableDynamic(dynamic: ColorScheme, dark: Boolean): ColorScheme =
    if (dark) {
        dynamic.copy(
            // Buttons stay on the wallpaper's primary, as before.
            primaryFixed = dynamic.primary,
            onPrimaryFixed = dynamic.onPrimary,
            error = Color(0xFFFF6A70),
            errorContainer = Color(0xFF651114),
            onErrorContainer = Color(0xFFFFE2E2),
            onSurfaceVariant = lerp(dynamic.surface, dynamic.onSurface, 0.88f),
            outline = lerp(dynamic.surface, dynamic.outline, 0.78f),
            outlineVariant = lerp(dynamic.surface, dynamic.outlineVariant, 0.82f),
            surfaceContainerLow = dynamic.surfaceContainer,
            surfaceContainer = dynamic.surfaceContainerHigh,
            surfaceContainerHigh = dynamic.surfaceContainerHighest,
            surfaceContainerLowest = Color.Black,
        )
    } else {
        dynamic.copy(
            primaryFixed = dynamic.primary,
            onPrimaryFixed = dynamic.onPrimary,
            error = Color(0xFFE34A55),
            errorContainer = Color(0xFFFFE2E2),
            onErrorContainer = Color(0xFF4C0608),
            onSurfaceVariant = lerp(dynamic.surface, dynamic.onSurface, 0.72f),
            outline = lerp(dynamic.surface, dynamic.outline, 0.66f),
            outlineVariant = lerp(dynamic.surface, dynamic.outlineVariant, 0.72f),
            background = dynamic.surfaceContainerHigh,
            surfaceContainerLowest = dynamic.surface,
            surfaceContainerLow = dynamic.surface,
            surfaceContainer = dynamic.surfaceContainerLow,
            surfaceContainerHigh = dynamic.surfaceContainer,
        )
    }
