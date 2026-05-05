package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beamburst.casswatch.theme.Spacing

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = Spacing.xs,
    elevation: Dp = 1.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val borderModifier = if (borderWidth > 0.dp) {
        Modifier.topBorder(borderWidth, borderColor)
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .padding(padding)
            .clip(shape)
            .then(borderModifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(elevation),
    ) {
        content()
    }
}

private fun Modifier.topBorder(
    borderWidth: Dp,
    borderColor: Color,
): Modifier = drawWithCache {
    val strokeWidth = borderWidth.toPx()
    val halfStroke = strokeWidth / 2f
    val cornerRadius = 8.dp.toPx()
    val clipBottom = cornerRadius + strokeWidth

    onDrawWithContent {
        drawContent()
        clipRect(bottom = clipBottom) {
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(halfStroke, halfStroke),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
