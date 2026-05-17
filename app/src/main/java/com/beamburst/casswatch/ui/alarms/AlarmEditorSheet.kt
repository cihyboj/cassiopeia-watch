package com.beamburst.casswatch.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.verticalScroll
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppDayPicker
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTimeWheelPicker
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorSheet(
    draft: AlarmDraft,
    sheetState: SheetState,
    onSave: (hour: Int, minute: Int, name: String, days: Set<DayOfWeek>) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember(draft) { mutableStateOf(draft.name) }
    var selectedDays by remember(draft) { mutableStateOf(draft.days) }
    var selectedHour by remember(draft) { mutableIntStateOf(draft.hour) }
    var selectedMinute by remember(draft) { mutableIntStateOf(draft.minute) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            AppText(
                text = stringResource(R.string.alarm_editor_title),
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(Spacing.lg))

            AppTimeWheelPicker(
                hour = selectedHour,
                minute = selectedMinute,
                onHourChange = { selectedHour = it },
                onMinuteChange = { selectedMinute = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.alarm_editor_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.md))
            AppText(
                text = stringResource(R.string.alarm_editor_repeat),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            AppDayPicker(
                selectedDays = selectedDays,
                onDayClick = { day ->
                    selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.xl))

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)
            ) {
                AppButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier
                )
                AppButton(
                    text = stringResource(R.string.alarm_editor_save),
                    onClick = {
                        onSave(selectedHour, selectedMinute, label.trim(), selectedDays)
                    },
                    modifier = Modifier
                )
            }
        }
    }
}
