package com.beamburst.casswatch.ui.alarms

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Confirmation persists across daily rings, until replaced or invalidated. */
internal fun watchHasOccurrence(
    key: AlarmKey,
    alarm: AlarmSyncStorage.StoredAlarm,
    instant: Long,
    payloads: Map<Int, WatchAlarmPayload>,
    now: Long,
    zone: ZoneId,
): Boolean {
    val payload = payloads[key.slot] ?: return false
    if (payload.address != key.normalizedAddress || !payload.enabled || !payload.daily || payload.syncedAt > now) return false
    if (payload.zoneId != zone.id || payload.utcOffsetSeconds != zone.rules.getOffset(Instant.ofEpochMilli(instant)).totalSeconds) return false
    val fireDate = Instant.ofEpochMilli(instant).atZone(zone).toLocalDate()
    if (AlarmOccurrence(fireDate, alarm.hour, alarm.minute).resolveInstant(zone).toEpochMilli() != instant) return false
    val time = alarmTimeAtOccurrence(instant, zone)
    return payload.hour == time.hour && payload.minute == time.minute
}

internal fun validFallbackCallback(
    key: AlarmKey,
    addresses: List<String>,
    state: AlarmState,
    expectedInstant: Long,
    expectedHash: String,
    clock: Clock,
    zone: ZoneId,
): Boolean {
    if (key.normalizedAddress !in addresses) return false
    val alarm = state.alarms.getOrNull(key.slot) ?: return false
    if (fallbackWakeAt(expectedInstant, clock.millis()) == null || clock.millis() < expectedInstant - 5 * 60 * 1000L) return false
    if (expectedHash != alarmHash(alarm.hour, alarm.minute, emptySet(), true)) return false
    if (alarm.fallbackAttemptAt == expectedInstant ||
        (alarm.fallbackStatus == "failed" && alarm.fallbackStatusAt == expectedInstant)) return false
    return nextAlarmOccurrence(alarm, state.days[key.slot] ?: AlarmSchedulePlanner.AllDays, clock, zone)?.toEpochMilli() == expectedInstant
}
