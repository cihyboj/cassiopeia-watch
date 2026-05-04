package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime

suspend fun applyHourlySignalToWatch(
    api: IGShockAPI,
    settings: HourlySignalSettings,
    now: LocalDateTime,
) {
    val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
    if (WatchInfo.chimeInSettings) {
        val current = api.getSettings()
        if (current.hourlyChime != target) {
            api.setSettings(current.copy(hourlyChime = target))
        }
    } else {
        val current = api.getAlarms()
        if (current.isNotEmpty() && current.first().hasHourlyChime != target) {
            api.setAlarms(ArrayList(AlarmSchedulePlanner.mirrorChime(current, target)))
        }
    }
}
