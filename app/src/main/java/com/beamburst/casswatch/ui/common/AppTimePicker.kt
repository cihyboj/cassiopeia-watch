package com.beamburst.casswatch.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beamburst.casswatch.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePicker(
    modifier: Modifier = Modifier,
    onConfirm: (TimePickerState) -> Unit,
    onDismiss: () -> Unit,
    initialHour: Int = 0,
    initialMinute: Int = 0,
    is24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
    backgroundColor: Color = MaterialTheme.colorScheme.background
) {
    val validInitialHour = remember(initialHour) {
        initialHour.coerceIn(0..23)
    }

    val validInitialMinute = remember(initialMinute) {
        initialMinute.coerceIn(0..59)
    }

    val timePickerState = rememberTimePickerState(
        initialHour = validInitialHour,
        initialMinute = validInitialMinute,
        is24Hour = is24Hour
    )
    var selectedHour by remember { mutableIntStateOf(validInitialHour) }
    var selectedMinute by remember { mutableIntStateOf(validInitialMinute) }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        AppTimeWheelPicker(
            hour = selectedHour,
            minute = selectedMinute,
            onHourChange = { selectedHour = it },
            onMinuteChange = { selectedMinute = it },
            modifier = Modifier.padding(vertical = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row {
            AppButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.cancel)
            )

            Spacer(modifier = Modifier.width(8.dp))

            AppButton(
                onClick = {
                    timePickerState.hour = selectedHour
                    timePickerState.minute = selectedMinute
                    onConfirm(timePickerState)
                },
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.ok)
            )
        }
    }
}

@Composable
fun AppTimeWheelPicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hourValues = remember { (0..23).map { "%02d".format(it) } }
    val minuteValues = remember { (0..59).map { "%02d".format(it) } }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppWheelPicker(
            values = hourValues,
            selectedIndex = hour.coerceIn(0, 23),
            onSelectedIndexChange = onHourChange,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        AppWheelPicker(
            values = minuteValues,
            selectedIndex = minute.coerceIn(0, 59),
            onSelectedIndexChange = onMinuteChange,
            modifier = Modifier.width(88.dp),
        )
    }
}
