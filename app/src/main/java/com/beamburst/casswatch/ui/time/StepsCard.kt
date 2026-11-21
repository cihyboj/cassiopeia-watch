package com.beamburst.casswatch.ui.time

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppText
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Home card: today's steps against the goal, today by hour, and the last 7 days. */
@Composable
fun StepsCard(
    modifier: Modifier,
    stepsViewModel: StepsViewModel = hiltViewModel(),
) {
    val history by stepsViewModel.history.collectAsState()
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(today) {
        // Roll "today" over at midnight while the screen stays open.
        val now = LocalDateTime.now()
        delay(Duration.between(now, today.plusDays(1).atStartOfDay()).toMillis().coerceAtLeast(1_000))
        today = LocalDate.now()
    }
    val model = history?.let { stepsChartModel(it, today) } ?: return
    StepsCardContent(modifier, model)
}

@Composable
internal fun StepsCardContent(modifier: Modifier, model: StepsChartModel) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    AppCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AppText(text = stringResource(R.string.steps_title), style = MaterialTheme.typography.titleMedium)
            val steps = model.todaySteps?.let(numbers::format) ?: "–"
            AppText(
                text = model.goal?.let { stringResource(R.string.steps_today_of_goal, steps, numbers.format(it)) }
                    ?: stringResource(R.string.steps_today, steps),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (model.goal != null && model.goal > 0) {
                LinearProgressIndicator(
                    progress = { ((model.todaySteps ?: 0).toFloat() / model.goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AppText(text = stringResource(R.string.steps_by_hour), style = MaterialTheme.typography.labelLarge)
            if (model.hourly.any { it != null }) {
                BarChart(values = model.hourly, goalLine = null, modifier = Modifier.fillMaxWidth().height(72.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "24").forEach { ChartLabel(it) }
                }
            } else {
                AppText(
                    text = stringResource(R.string.steps_no_hourly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppText(text = stringResource(R.string.steps_last_7_days), style = MaterialTheme.typography.labelLarge)
            BarChart(
                values = model.week.map { it.second },
                goalLine = model.goal,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                model.week.forEach { (date, _) ->
                    ChartLabel(
                        date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        Modifier.weight(1f),
                    )
                }
            }

            model.lastReadAtMs?.let { ms ->
                val time = remember(ms) {
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
                }
                AppText(
                    text = stringResource(R.string.steps_last_read, time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartLabel(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Plain bar chart; `null` values (no data) leave a gap, the optional goal is a dashed line. */
@Composable
private fun BarChart(values: List<Int?>, goalLine: Int?, modifier: Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.tertiary
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val max = maxOf(values.maxOf { it ?: 0 }, goalLine ?: 0, 1).toFloat()
        val slot = size.width / values.size
        val barWidth = slot * 0.7f
        values.forEachIndexed { i, value ->
            val left = i * slot + (slot - barWidth) / 2
            drawRoundRect(
                color = baseColor,
                topLeft = Offset(left, size.height - 2f),
                size = Size(barWidth, 2f),
            )
            if (value != null && value > 0) {
                val h = size.height * value / max
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 4, barWidth / 4),
                )
            }
        }
        goalLine?.takeIf { it > 0 }?.let { goal ->
            val y = size.height - size.height * goal / max
            drawLine(
                color = goalColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}
