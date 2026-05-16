package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter

private val ItemHeight: Dp = 52.dp

/**
 * Vertical scroll drum that snaps to integer positions.
 *
 * Inserts invisible sentinel items at start and end so the first and last
 * real items can be scrolled to the center position.
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onIndexSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val allItems = listOf("") + items + listOf("")

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, items.lastIndex),
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(selectedIndex) {
        if (listState.firstVisibleItemIndex != selectedIndex) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val newIndex = listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
                if (newIndex != selectedIndex) onIndexSelected(newIndex)
            }
    }

    val interceptor = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput) available else Offset.Zero
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        userScrollEnabled = enabled,
        modifier = modifier
            .height(ItemHeight * 3)
            .nestedScroll(interceptor),
    ) {
        itemsIndexed(allItems) { index, item ->
            val isSelected = index == selectedIndex + 1
            Box(
                modifier = Modifier
                    .height(ItemHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item,
                    style = if (isSelected) MaterialTheme.typography.headlineLarge
                            else MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (enabled) 0.35f else 0.15f
                            ),
                )
            }
        }
    }
}
