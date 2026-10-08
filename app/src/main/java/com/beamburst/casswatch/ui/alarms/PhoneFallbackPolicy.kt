package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Only the latest successful payload for this watch, at this exact slot, is evidence. */
internal fun watchHasOccurrence(
    key: AlarmKey,
    alarm: AlarmSyncStorage.StoredAlarm,
    instant: Long,
    history: List<SyncEntry>,
    now: Long,
    zone: ZoneId,
): Boolean {
    val entry = history.filter { it.success && SyncKind.ALARMS in it.kinds &&
        AlarmKey.from(it.deviceAddress, key.slot)?.normalizedAddress == key.normalizedAddress && it.timestamp <= now }
        .maxByOrNull { it.timestamp } ?: return false
    if (entry.details.alarmHashes?.getOrNull(key.slot) != alarmHash(alarm.hour, alarm.minute, emptySet(), true)) return false
    // A watch alarm is daily. A previous payload only confirms its first upcoming ring;
    // future weekly occurrences still need a fresh sync with the right enabled state.
    val atSync = Clock.fixed(Instant.ofEpochMilli(entry.timestamp), zone)
    return AlarmOccurrence.nextOccurrence(alarm.hour, alarm.minute, atSync, zone).resolveInstant(zone).toEpochMilli() == instant
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
    if (alarm.fallbackAttemptAt == expectedInstant) return false
    return nextAlarmOccurrence(alarm, state.days[key.slot] ?: AlarmSchedulePlanner.AllDays, clock, zone)?.toEpochMilli() == expectedInstant
}
