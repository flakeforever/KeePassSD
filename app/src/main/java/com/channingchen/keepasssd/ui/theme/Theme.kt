package com.channingchen.keepasssd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalNeumorphicColors = compositionLocalOf {
    NeumorphicColors(
        background = NeumorphicBackground,
        lightShadow = NeumorphicLightShadow,
        darkShadow = NeumorphicDarkShadow,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        accent = Accent
    )
}

data class NeumorphicColors(
    val background: androidx.compose.ui.graphics.Color,
    val lightShadow: androidx.compose.ui.graphics.Color,
    val darkShadow: androidx.compose.ui.graphics.Color,
    val textPrimary: androidx.compose.ui.graphics.Color,
    val textSecondary: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color
)

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    background = NeumorphicBackground,
    surface = NeumorphicBackground,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun KeePassSDTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalNeumorphicColors provides LocalNeumorphicColors.current
    ) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}