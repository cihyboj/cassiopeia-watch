package com.beamburst.casswatch.ui.alarms

import android.graphics.Paint
import android.widget.EditText
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppText

private val START_HOURS       = intArrayOf(1, 7, 13, 19)
private val END_HOURS         = intArrayOf(6, 12, 18, 0)
private val START_DISPLAYED   = arrayOf("01", "07", "13", "19")
private val END_DISPLAYED_ALL = arrayOf("06", "12", "18", "00")

private fun NumberPicker.applyTextStyle(colorArgb: Int, textSizePx: Float) {
    runCatching {
        val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
        paintField.isAccessible = true
        (paintField.get(this) as? Paint)?.apply {
            color = colorArgb
            textSize = textSizePx
        }
    }
    runCatching {
        val inputField = NumberPicker::class.java.getDeclaredField("mInputText")
        inputField.isAccessible = true
        (inputField.get(this) as? EditText)?.apply {
            setTextColor(colorArgb)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }
    }
    invalidate()
}

private fun validEndIndicesFor(startIndex: Int): List<Int> {
    val s = START_HOURS[startIndex]
    return END_HOURS.indices.filter { i ->
        val endNorm = if (END_HOURS[i] == 0) 24 else END_HOURS[i]
        endNorm > s
    }
}

@Composable
fun HourlySignalDialog(
    currentStartHour: Int,
    currentEndHour: Int,
    onSave: (startHour: Int, endHour: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialStartIndex = START_HOURS.indexOf(currentStartHour).coerceAtLeast(0)
    val initialValidEnds  = validEndIndicesFor(initialStartIndex)
    val initialEndLocalIndex = initialValidEnds
        .indexOfFirst { END_HOURS[it] == currentEndHour }
        .coerceAtLeast(0)

    var selectedStartIndex     by remember { mutableIntStateOf(initialStartIndex) }
    var selectedEndLocalIndex  by remember { mutableIntStateOf(initialEndLocalIndex) }

    // Capture theme values here (composable scope) for use inside AndroidView factory
    val pickerTextColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val pickerTextSizePx    = with(LocalDensity.current) { 18.sp.toPx() }

    fun currentStartHourValue() = START_HOURS[selectedStartIndex]
    fun currentEndHourValue(): Int {
        val validEnds = validEndIndicesFor(selectedStartIndex)
        val globalIdx = validEnds.getOrElse(selectedEndLocalIndex) { validEnds.first() }
        return END_HOURS[globalIdx]
    }

    fun chimeCount(): Int {
        val s = currentStartHourValue()
        val e = currentEndHourValue()
        val endNorm = if (e == 0) 24 else e
        return (endNorm - s + 1).coerceAtLeast(0)  // inclusive
    }

    fun chimeHoursText(): String {
        val s = currentStartHourValue()
        val e = currentEndHourValue()
        val endNorm = if (e == 0) 24 else e
        return (s..endNorm).joinToString(", ") { h ->  // inclusive
            String.format("%02d:00", if (h == 24) 0 else h)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.hourly_signal_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AndroidView(
                            factory = { ctx ->
                                NumberPicker(ctx).apply {
                                    minValue = 0
                                    maxValue = START_DISPLAYED.size - 1
                                    displayedValues = START_DISPLAYED
                                    value = initialStartIndex
                                    setOnValueChangedListener { _, _, newVal ->
                                        selectedStartIndex = newVal
                                        selectedEndLocalIndex = 0
                                    }
                                    applyTextStyle(pickerTextColorArgb, pickerTextSizePx)
                                }
                            }
                        )
                        AppText(
                            text = stringResource(R.string.hourly_signal_picker_start),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AppText(
                        text = "to",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AndroidView(
                            factory = { ctx ->
                                NumberPicker(ctx).apply {
                                    val validEnds = validEndIndicesFor(initialStartIndex)
                                    val displayed = validEnds
                                        .map { END_DISPLAYED_ALL[it] }
                                        .toTypedArray()
                                    minValue = 0
                                    maxValue = displayed.size - 1
                                    displayedValues = displayed
                                    value = initialEndLocalIndex
                                    setOnValueChangedListener { _, _, newVal ->
                                        selectedEndLocalIndex = newVal
                                    }
                                    applyTextStyle(pickerTextColorArgb, pickerTextSizePx)
                                }
                            },
                            update = { picker ->
                                val validEnds = validEndIndicesFor(selectedStartIndex)
                                val displayed = validEnds
                                    .map { END_DISPLAYED_ALL[it] }
                                    .toTypedArray()
                                // Clear values before changing max to avoid index-out-of-range
                                picker.displayedValues = null
                                picker.minValue = 0
                                picker.maxValue = displayed.size - 1
                                picker.displayedValues = displayed
                                picker.value = selectedEndLocalIndex.coerceIn(0, displayed.size - 1)
                            }
                        )
                        AppText(
                            text = stringResource(R.string.hourly_signal_picker_end),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AppText(
                    text = stringResource(
                        R.string.hourly_signal_chimes_at,
                        chimeHoursText(),
                        chimeCount()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AppText(
                    text = stringResource(R.string.hourly_signal_requires_auto_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            AppButton(
                text = stringResource(android.R.string.ok),
                onClick = { onSave(currentStartHourValue(), currentEndHourValue()) }
            )
        },
        dismissButton = {
            AppButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss
            )
        }
    )
}
