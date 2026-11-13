package com.product.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ProductTokens {
    object Color {
        val BrandAccent = Color(0xFF6A5A5B)
        val BrandAccentDark = Color(0xFFD7C1C4)
        val Warning = Color(0xFF946200)
        val WarningDark = Color(0xFFFFD166)
        val Success = Color(0xFF2E7D32)
        val SuccessDark = Color(0xFF81C784)
        val LightBackground = Color(0xFFF0E6E5)
        val LightSurface = Color(0xFFFFF8F6)
        val LightMutedSurface = Color(0xFFFFF8F6)
        val LightText = Color(0xFF211A1A)
        val LightMutedText = Color(0xFF655B5C)
        val DarkBackground = Color(0xFF110F10)
        val DarkSurface = Color(0xFF1B1A18)
        val DarkMutedSurface = Color(0xFF282325)
        val DarkText = Color(0xFFECE2E1)
        val DarkMutedText = Color(0xFFCFC5C4)
    }

    object Spacing {
        val Xxs = 2.dp
        val Xs = 3.dp
        val Sm = 4.dp
        val Md = 6.dp
        val Lg = 8.dp
        val Xl = 10.dp
        val Xxl = 12.dp
        val Section = 14.dp
        val RowContent = 16.dp
        val Screen = 18.dp
    }

    object Radius {
        val Xs = 6.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Card = Lg
        val Xl = 24.dp
        val RowGroup = 22.dp
        val GroupedRow = 4.dp
        val Sheet = 28.dp
    }

    object Size {
        val TouchTarget = 48.dp
        val Row = 48.dp
        val TopBarNavigationIcon = 20.dp
        val TopBarNavigationSurface = 38.dp
        val TopBarActionIcon = 18.dp
        val GroupedRowIcon = 22.dp
        val SettingsRow = 62.dp
        val SheetAction = 52.dp
        val SlotIcon = 14.dp
        val ActiveIcon = 18.dp
    }

    object Alpha {
        const val MutedIcon = 0.72f
        const val Divider = 0.30f
        const val SelectedSurface = 0.64f
    }
}
