package com.channingchen.keepasssd.ui.theme

import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.neumorphic(
    backgroundColor: Color,
    lightShadowColor: Color,
    darkShadowColor: Color,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 6.dp,
    isPressed: Boolean = false
) = this.drawBehind {
    val cornerRadiusPx = cornerRadius.toPx()
    val elevationPx = elevation.toPx()
    
    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.color = backgroundColor.toArgb()
        paint.isAntiAlias = true
        
        if (!isPressed) {
            // Light shadow
            paint.setShadowLayer(
                elevationPx,
                -elevationPx / 2,
                -elevationPx / 2,
                lightShadowColor.toArgb()
            )
            canvas.nativeCanvas.drawRoundRect(
                0f, 0f, size.width, size.height,
                cornerRadiusPx, cornerRadiusPx,
                paint
            )
            
            // Dark shadow
            paint.setShadowLayer(
                elevationPx,
                elevationPx / 2,
                elevationPx / 2,
                darkShadowColor.toArgb()
            )
            canvas.nativeCanvas.drawRoundRect(
                0f, 0f, size.width, size.height,
                cornerRadiusPx, cornerRadiusPx,
                paint
            )
        } else {
            // Pressed state: recessed
            paint.setShadowLayer(
                elevationPx / 2,
                -elevationPx / 4,
                -elevationPx / 4,
                darkShadowColor.copy(alpha = 0.5f).toArgb()
            )
            canvas.nativeCanvas.drawRoundRect(
                0f, 0f, size.width, size.height,
                cornerRadiusPx, cornerRadiusPx,
                paint
            )
        }
        
        // Draw the main background so it's opaque
        paint.clearShadowLayer()
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            cornerRadiusPx, cornerRadiusPx,
            paint
        )
    }
}

@Composable
fun NeumorphicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    innerPadding: Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = LocalNeumorphicColors.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = modifier
            .neumorphic(
                backgroundColor = colors.background,
                lightShadowColor = colors.lightShadow,
                darkShadowColor = colors.darkShadow,
                cornerRadius = cornerRadius,
                isPressed = isPressed
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null, // No default ripple
                onClick = onClick
            )
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun NeumorphicIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    iconTint: Color = LocalNeumorphicColors.current.textPrimary
) {
    NeumorphicButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        cornerRadius = cornerRadius,
        innerPadding = 0.dp
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint
        )
    }
}

@Composable
fun NeumorphicCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    innerPadding: Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val colors = LocalNeumorphicColors.current
    
    Box(
        modifier = modifier
            .neumorphic(
                backgroundColor = colors.background,
                lightShadowColor = colors.lightShadow,
                darkShadowColor = colors.darkShadow,
                cornerRadius = cornerRadius,
                isPressed = false
            )
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
