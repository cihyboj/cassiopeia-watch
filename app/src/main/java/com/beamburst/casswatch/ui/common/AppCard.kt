package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
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
        Modifier.topBorder(borderWidth, borderColor, shape)
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
    shape: Shape,
): Modifier = drawWithCache {
    val strokeWidth = borderWidth.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    val borderPath = when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
    val topCornerHeight = when (outline) {
        is Outline.Rectangle -> 0f
        is Outline.Rounded -> maxOf(
            outline.roundRect.topLeftCornerRadius.y,
            outline.roundRect.topRightCornerRadius.y
        )
        is Outline.Generic -> 24.dp.toPx()
    }
    val clipBottom = minOf(size.height, topCornerHeight + strokeWidth * 2f)

    onDrawWithContent {
        drawContent()
        clipRect(bottom = clipBottom) {
            drawPath(
                path = borderPath,
                color = borderColor,
                style = Stroke(width = strokeWidth * 2f),
            )
        }
    }
}
