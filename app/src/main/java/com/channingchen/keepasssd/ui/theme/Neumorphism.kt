package com.channingchen.keepasssd.ui.theme

import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
    enabled: Boolean = true,
    cornerRadius: Dp = 16.dp,
    innerPadding: Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = LocalNeumorphicColors.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val displayAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .alpha(displayAlpha)
            .neumorphic(
                backgroundColor = colors.background,
                lightShadowColor = colors.lightShadow,
                darkShadowColor = colors.darkShadow,
                cornerRadius = cornerRadius,
                isPressed = if (enabled) isPressed else false
            )
            .clickable(
                enabled = enabled,
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
    enabled: Boolean = true,
    cornerRadius: Dp = 16.dp,
    iconTint: Color = LocalNeumorphicColors.current.textPrimary
) {
    NeumorphicButton(
        onClick = onClick,
        enabled = enabled,
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
    isPressed: Boolean = false,
    backgroundColor: Color = LocalNeumorphicColors.current.background,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val colors = LocalNeumorphicColors.current
    
    Box(
        modifier = modifier
            .neumorphic(
                backgroundColor = backgroundColor,
                lightShadowColor = colors.lightShadow,
                darkShadowColor = colors.darkShadow,
                cornerRadius = cornerRadius,
                isPressed = isPressed
            )
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun EngravedText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textColor: Color? = null
) {
    val colors = LocalNeumorphicColors.current
    val fill = textColor ?: colors.textPrimary // keep the original dark gray

    Box(modifier = modifier) {
        // Bottom-Right Highlight (pokes out from the bottom-right edge)
        Text(
            text = text,
            color = colors.lightShadow.copy(alpha = 0.9f),
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = Modifier.offset(x = 1.5.dp, y = 1.5.dp)
        )
        // Top-Left Shadow (pokes out from the top-left edge)
        Text(
            text = text,
            color = colors.darkShadow.copy(alpha = 0.9f),
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = Modifier.offset(x = (-1).dp, y = (-1).dp)
        )
        // Main Body (The floor of the engraving)
        Text(
            text = text,
            color = fill,
            fontSize = fontSize,
            fontWeight = fontWeight
        )
    }
}
