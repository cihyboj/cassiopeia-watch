package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek

val AppSelectedYellow = Color(0xFFFFB020)
private val AppSelectedYellowText = Color(0xFF1F1600)

@Composable
fun AppDayPicker(
    selectedDays: Set<DayOfWeek>,
    onDayClick: ((DayOfWeek) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in selectedDays
            AppTogglePill(
                label = day.twoLetterLabel(),
                selected = selected,
                enabled = enabled,
                onClick = onDayClick?.let { { it(day) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

fun DayOfWeek.twoLetterLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Mo"
    DayOfWeek.TUESDAY -> "Tu"
    DayOfWeek.WEDNESDAY -> "We"
    DayOfWeek.THURSDAY -> "Th"
    DayOfWeek.FRIDAY -> "Fr"
    DayOfWeek.SATURDAY -> "Sa"
    DayOfWeek.SUNDAY -> "Su"
}

@Composable
fun AppTogglePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    val selectedTextColor = AppSelectedYellowText.copy(alpha = if (enabled) 1f else 0.7f)
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.76f else 0.42f)
    val selectedContainer = AppSelectedYellow.copy(alpha = if (enabled) 1f else 0.45f)
    val unselectedContainer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.28f else 0.14f)
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(shape)
            .background(if (selected) selectedContainer else unselectedContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 0.55f), shape)
            .then(clickModifier)
            .padding(horizontal = 0.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) selectedTextColor else unselectedTextColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun AlarmWeekdayStrip(
    selectedDays: Set<DayOfWeek>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in selectedDays
            Text(
                text = day.singleLetterLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    AppSelectedYellow.copy(alpha = if (enabled) 1f else 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.42f else 0.24f)
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private fun DayOfWeek.singleLetterLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}
