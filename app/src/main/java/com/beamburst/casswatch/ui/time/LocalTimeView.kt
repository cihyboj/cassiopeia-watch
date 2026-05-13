package com.beamburst.casswatch.ui.time

import RealTimeClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTextLarge
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppCard
import java.util.TimeZone

@Composable
fun LocalTimeView(
    modifier: Modifier,
    timeModel: TimeViewModel = hiltViewModel()
) {
    val clockSource by timeModel.clockDisplaySource.collectAsState()
    LocalTimeCardContent(
        modifier = modifier,
        clockSource = clockSource,
        onSendTime = { timeModel.onAction(TimeAction.SendTimeToWatch) },
    )
}

@Composable
fun LocalTimeCardContent(
    modifier: Modifier,
    clockSource: ClockDisplaySource = ClockDisplaySource(),
    onSendTime: () -> Unit,
) {
    AppCard(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // First Column: Local Time and TimeZoneTextView
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.lg),
            ) {
                AppText(
                    text = stringResource(
                        id = R.string.local_time
                    ),
                )

                TextClockComposable(
                    clockSource = clockSource,
                    modifier = Modifier.align(Alignment.Start)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TimeZoneTextView()
                    TimeSourceBadge(clockSource = clockSource)
                }
            }

            // Second Column: Send Time Button
            Column(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(horizontal = Spacing.sm),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SendTimeButton(
                    text = stringResource(
                        id = R.string.send_to_watch
                    ),
                    onClick = onSendTime,
                )
            }
        }
    }
}

@Composable
fun TextClockComposable(
    clockSource: ClockDisplaySource = ClockDisplaySource(),
    modifier: Modifier = Modifier,
) {
    RealTimeClock(source = clockSource, modifier = modifier)
}

@Composable
fun TimeZoneTextView(modifier: Modifier = Modifier) {
    var timeZoneId by remember { mutableStateOf(TimeZone.getDefault().id) }

    LaunchedEffect(Unit) {
        while (true) {
            timeZoneId = TimeZone.getDefault().id
            kotlinx.coroutines.delay(1000L)
        }
    }

    AppText(
        text = timeZoneId,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun TimeSourceBadge(clockSource: ClockDisplaySource, modifier: Modifier = Modifier) {
    val usingNtp = clockSource.isUsingNtp(android.os.SystemClock.elapsedRealtime())
    val label = stringResource(
        if (usingNtp) R.string.time_source_badge_ntp
        else R.string.time_source_badge_phone,
    )
    AppText(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Spacing.xs),
            )
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
    )
}

@Composable
fun SendTimeButton(text: String, onClick: () -> Unit) {
    AppButton(
        onClick = {
            onClick()
        },
        text = text
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewLocalTimeCard() {
    com.beamburst.casswatch.theme.CassiopeiaWatchTheme(dynamicColor = false) {
        LocalTimeCardContent(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .padding(vertical = Spacing.xxs),
            clockSource = ClockDisplaySource(),
            onSendTime = {},
        )
    }
}
