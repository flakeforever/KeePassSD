package com.channingchen.keepasssd.ui.theme

import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.unit.sp

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

fun Modifier.neumorphicBorder(
    lightShadowColor: Color,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.5.dp,
    alpha: Float = 0.9f
) = this.drawWithContent {
    drawContent()
    val cornerRadiusPx = cornerRadius.toPx()
    val strokeWidthPx = borderWidth.toPx()
    
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(
            Color.Transparent, // Completely dim at light source
            lightShadowColor.copy(alpha = alpha)  // Very pronounced at shadow side
        ),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(size.width, size.height)
    )
    
    val halfStroke = strokeWidthPx / 2
    drawRoundRect(
        brush = brush,
        topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
        size = androidx.compose.ui.geometry.Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx - halfStroke, cornerRadiusPx - halfStroke),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx)
    )
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
    hasPremiumBorder: Boolean = false,
    borderAlpha: Float = 0.6f,
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
            .then(
                if (hasPremiumBorder) {
                    Modifier.neumorphicBorder(
                        lightShadowColor = colors.lightShadow,
                        cornerRadius = cornerRadius,
                        alpha = borderAlpha
                    )
                } else Modifier
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
    textColor: Color? = null,
    alpha: Float = 1f
) {
    val colors = LocalNeumorphicColors.current
    val fill = textColor ?: colors.textPrimary // keep the original dark gray

    Box(modifier = modifier.alpha(alpha)) {
        // Bottom-Right Highlight (pokes out from the bottom-right edge)
        Text(
            text = text,
            color = colors.lightShadow.copy(alpha = 0.9f),
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = Modifier.offset(x = 1.2.dp, y = 1.2.dp)
        )
        // Top-Left Shadow (pokes out from the top-left edge)
        Text(
            text = text,
            color = colors.darkShadow.copy(alpha = 0.9f),
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = Modifier.offset(x = (-0.8).dp, y = (-0.8).dp)
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

@Composable
fun NeumorphicSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalNeumorphicColors.current
    val haptic = LocalHapticFeedback.current
    
    val trackWidth = 72.dp
    val trackHeight = 36.dp
    val thumbSize = 30.dp
    val padding = 3.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - padding else padding,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ThumbOffset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.background else Color(0xFFE57373), // Red-ish for off, light for on
        animationSpec = tween(300),
        label = "TrackColor"
    )

    val onTextAlpha by animateFloatAsState(if (checked) 0.7f else 0f, label = "OnAlpha")
    val offTextAlpha by animateFloatAsState(if (checked) 0f else 0.7f, label = "OffAlpha")

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(!checked) 
                }
            )
    ) {
        // Recessed Track
        NeumorphicCard(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 18.dp,
            innerPadding = 0.dp,
            isPressed = true, // Force recessed look
            backgroundColor = trackColor
        ) {
            // Text Layer
            Box(modifier = Modifier.fillMaxSize()) {
                EngravedText(
                    text = "On",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    textColor = colors.textSecondary.copy(alpha = 0.5f),
                    alpha = onTextAlpha
                )
                EngravedText(
                    text = "Off",
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    textColor = colors.darkShadow.copy(alpha = 0.6f),
                    alpha = offTextAlpha
                )
            }
        }

        // Raised Thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .neumorphic(
                    backgroundColor = colors.background,
                    lightShadowColor = colors.lightShadow,
                    darkShadowColor = colors.darkShadow,
                    cornerRadius = 8.dp,
                    elevation = 4.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            // Dots (Grip)
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    GripDot()
                    GripDot()
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    GripDot()
                    GripDot()
                }
            }
        }
    }
}

@Composable
private fun GripDot() {
    val colors = LocalNeumorphicColors.current
    Box(
        modifier = Modifier
            .size(4.dp)
            .drawBehind {
                drawCircle(color = colors.darkShadow.copy(alpha = 0.3f), radius = 2.dp.toPx())
            }
    )
}
