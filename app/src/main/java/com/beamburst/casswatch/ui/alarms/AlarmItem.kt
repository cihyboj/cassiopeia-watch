package com.beamburst.casswatch.ui.alarms

import androidx.compose.ui.res.stringResource
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTextVeryLarge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AlarmWeekdayStrip
import com.beamburst.casswatch.theme.Spacing
import java.time.DayOfWeek
import java.util.Locale

@Composable
fun AlarmItem(
    hours: Int = 12,
    minutes: Int = 0,
    name: String? = null,
    isAlarmEnabled: Boolean = true,
    showAsEnabled: Boolean = isAlarmEnabled,
    onToggleAlarm: (Boolean) -> Unit,
    onTap: () -> Unit,
    showDaySelector: Boolean = false,
    selectedDays: Set<DayOfWeek> = emptySet(),
    syncWarning: String? = null,
) {
    var isEnabled by remember(isAlarmEnabled) { mutableStateOf(isAlarmEnabled) }

    val strip = useAlarmWeekdayStrip(selectedDays, showDaySelector)

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs)
            .clickable { onTap() },
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppTextVeryLarge(
                    text = String.format(Locale.US, "%02d:%02d", hours, minutes),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppSwitch(
                    checked = showAsEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        onToggleAlarm(checked)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = Spacing.sm)
                        .wrapContentHeight(),
                ) {
                    if (!name.isNullOrBlank()) {
                        AppText(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (strip) {
                    AlarmWeekdayStrip(
                        selectedDays = selectedDays,
                        enabled = isEnabled,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    AppText(
                        text = alarmRepeatLabel(selectedDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selectedDays.isEmpty()) {
                AppText(text = stringResource(R.string.alarm_one_time_watch_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AlarmSyncWarningText(syncWarning)
        }
    }
}

@Composable
private fun AlarmSyncWarningText(syncWarning: String?) {
    if (syncWarning.isNullOrBlank()) return
    AppText(
        text = syncWarning,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(
            start = Spacing.md,
            end = Spacing.md,
            bottom = Spacing.md
        )
    )
}
