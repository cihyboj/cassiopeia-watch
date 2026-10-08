package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime
import java.time.Clock
import java.time.ZoneId

suspend fun applyHourlySignalToWatch(
    api: IGShockAPI,
    settings: HourlySignalSettings,
    now: LocalDateTime,
    consumedSlots: Set<Int> = emptySet(),
    oneShotOccurrences: Map<Int, AlarmOccurrence> = emptyMap(),
    clock: Clock = Clock.systemUTC(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
    if (WatchInfo.chimeInSettings) {
        val current = api.getSettings()
        if (current.hourlyChime != target) {
            api.setSettings(current.copy(hourlyChime = target))
        }
    } else {
        val current = api.getAlarms()
        val payload = hourlySignalAlarmPayload(current, target, consumedSlots, oneShotOccurrences, clock, zone)
        if (payload != current) api.setAlarms(ArrayList(payload))
    }
}

internal fun hourlySignalAlarmPayload(
    alarms: List<Alarm>,
    target: Boolean,
    consumedSlots: Set<Int>,
    oneShotOccurrences: Map<Int, AlarmOccurrence>,
    clock: Clock,
    zone: ZoneId,
): List<Alarm> = AlarmSchedulePlanner.mirrorChime(alarms.mapIndexed { index, alarm ->
    if (index in consumedSlots || oneShotOccurrences[index]?.isElapsed(clock, zone) == true) alarm.copy(enabled = false) else alarm
}, target)
