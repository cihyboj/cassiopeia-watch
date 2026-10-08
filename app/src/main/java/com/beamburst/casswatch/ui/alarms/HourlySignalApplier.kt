package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime

suspend fun applyHourlySignalToWatch(
    api: IGShockAPI,
    settings: HourlySignalSettings,
    now: LocalDateTime,
    consumedSlots: Set<Int> = emptySet(),
) {
    val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
    if (WatchInfo.chimeInSettings) {
        val current = api.getSettings()
        if (current.hourlyChime != target) {
            api.setSettings(current.copy(hourlyChime = target))
        }
    } else {
        val current = api.getAlarms()
        val unconsumed = current.mapIndexed { index, alarm ->
            if (index in consumedSlots) alarm.copy(enabled = false) else alarm
        }
        val payload = AlarmSchedulePlanner.mirrorChime(unconsumed, target)
        if (payload != current) api.setAlarms(ArrayList(payload))
    }
}
