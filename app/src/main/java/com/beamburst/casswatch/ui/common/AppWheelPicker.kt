package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

@Composable
fun AppWheelPicker(
    values: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleItems: Int = 3,
    itemHeight: Dp = 42.dp,
    selectedFontSize: TextUnit = 38.sp,
    inactiveFontSize: TextUnit = 30.sp,
    selectedColor: Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    },
    inactiveColor: Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF7A7A7A)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    },
) {
    if (values.isEmpty()) return

    val safeSelectedIndex = selectedIndex.coerceIn(values.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeSelectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(
        lazyListState = listState,
        snapPosition = SnapPosition.Center,
    )
    val currentOnSelectedIndexChange by rememberUpdatedState(onSelectedIndexChange)
    var centeredIndex by remember(values, safeSelectedIndex) { mutableIntStateOf(safeSelectedIndex) }

    LaunchedEffect(values, safeSelectedIndex) {
        if (!listState.isScrollInProgress) {
            centeredIndex = safeSelectedIndex
            listState.scrollToCenteredItem(safeSelectedIndex)
        }
    }

    LaunchedEffect(listState, values) {
        snapshotFlow { listState.centeredItemIndex(values.lastIndex) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { index ->
                centeredIndex = index
            }
    }

    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    listState.centeredItemIndex(values.lastIndex)?.let { index ->
                        centeredIndex = index
                        listState.animateScrollToCenteredItem(index, values.lastIndex)
                        currentOnSelectedIndexChange(index)
                    }
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.height(itemHeight * visibleItems),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = itemHeight),
        flingBehavior = flingBehavior,
    ) {
        itemsIndexed(values) { index, label ->
            val selected = index == centeredIndex
            Text(
                text = label,
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = if (selected) selectedColor else inactiveColor,
                fontSize = if (selected) selectedFontSize else inactiveFontSize,
                lineHeight = itemHeight.toSp(),
            )
        }
    }
}

private fun LazyListState.centeredItemIndex(lastIndex: Int): Int? {
    val layoutInfo = layoutInfo
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo
        .minByOrNull { item ->
            abs(item.offset + item.size / 2 - viewportCenter)
        }
        ?.index
        ?.coerceIn(0, lastIndex)
}

private suspend fun LazyListState.scrollToCenteredItem(index: Int) {
    scrollToItem(index)
}

private suspend fun LazyListState.animateScrollToCenteredItem(index: Int, lastIndex: Int) {
    animateScrollToItem(index.coerceIn(0, lastIndex))
}

private fun Dp.toSp(): TextUnit = value.sp
