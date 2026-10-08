package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.utils.Utils
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** PendingIntent extras are payload only; this key supplies its identity. */
data class AlarmKey(val normalizedAddress: String, val slot: Int) {
    init {
        require(normalizedAddress.isNotBlank() && normalizedAddress == Utils.sanitizeMacAddress(normalizedAddress))
        require(slot >= 0)
    }

    val uri: String get() = "casswatch://alarm-fallback/$normalizedAddress/$slot"

    companion object {
        fun from(address: String?, slot: Int): AlarmKey? {
            val normalized = address?.let(Utils::sanitizeMacAddress).orEmpty()
            return if (normalized.isBlank() || slot < 0) null else AlarmKey(normalized, slot)
        }
    }
}

/** A wall-clock occurrence. atZone advances gaps and selects the earlier overlap offset. */
data class AlarmOccurrence(val localDate: LocalDate, val hour: Int, val minute: Int) {
    fun resolveInstant(zone: ZoneId): Instant = localDate.atTime(hour, minute).atZone(zone).toInstant()
    fun isElapsed(clock: Clock, zone: ZoneId): Boolean = !resolveInstant(zone).isAfter(clock.instant())

    companion object {
        fun nextOccurrence(hour: Int, minute: Int, clock: Clock, zone: ZoneId): AlarmOccurrence {
            val today = LocalDateTime.ofInstant(clock.instant(), zone).toLocalDate()
            val occurrence = AlarmOccurrence(today, hour, minute)
            return if (occurrence.resolveInstant(zone).isAfter(clock.instant())) occurrence
            else occurrence.copy(localDate = today.plusDays(1))
        }
    }
}

internal fun AlarmSyncStorage.StoredAlarm.occurrence(): AlarmOccurrence? = oneShotLocalDate?.let {
    AlarmOccurrence(LocalDate.parse(it), hour, minute)
}

/** Migration and expiry never roll an existing date forward, including after a clock change. */
internal fun reconcileAlarmState(state: AlarmState, clock: Clock, zone: ZoneId): AlarmState {
    val alarms = state.alarms.mapIndexed { slot, alarm ->
        if (!alarm.enabled || state.days[slot] != emptySet<java.time.DayOfWeek>() || alarm.firedAt != null) alarm
        else {
            val dated = if (alarm.oneShotLocalDate == null) alarm.copy(
                oneShotLocalDate = AlarmOccurrence.nextOccurrence(alarm.hour, alarm.minute, clock, zone).localDate.toString()
            ) else alarm
            if (dated.occurrence()!!.isElapsed(clock, zone)) dated.copy(firedAt = clock.millis()) else dated
        }
    }
    return state.copy(alarms = alarms, dirty = state.dirty || alarms != state.alarms)
}

internal fun editAlarmState(
    state: AlarmState,
    slot: Int,
    edited: AlarmSyncStorage.StoredAlarm,
    days: Set<java.time.DayOfWeek>,
    reenable: Boolean,
    clock: Clock,
    zone: ZoneId,
): AlarmState {
    val previous = state.alarms[slot]
    val rearm = days.isEmpty() && edited.enabled && (reenable || !previous.enabled ||
        previous.hour != edited.hour || previous.minute != edited.minute || state.days[slot] != days)
    val alarm = if (days.isNotEmpty()) edited.copy(
        oneShotLocalDate = null,
        firedAt = null,
        fallbackAttemptAt = if (!reenable && previous.hour == edited.hour && previous.minute == edited.minute && state.days[slot] == days)
            previous.fallbackAttemptAt else null,
    ) else if (rearm) edited.copy(
        oneShotLocalDate = AlarmOccurrence.nextOccurrence(edited.hour, edited.minute, clock, zone).localDate.toString(),
        firedAt = null,
        fallbackAttemptAt = null,
    ) else edited.copy(
        oneShotLocalDate = previous.oneShotLocalDate,
        firedAt = previous.firedAt,
        fallbackAttemptAt = if (previous.hour == edited.hour && previous.minute == edited.minute && state.days[slot] == days)
            previous.fallbackAttemptAt else null,
    )
    return state.copy(alarms = state.alarms.mapIndexed { index, old -> if (index == slot) alarm else old },
        days = state.days + (slot to days), dirty = true)
}

internal fun nextAlarmOccurrence(alarm: AlarmSyncStorage.StoredAlarm, days: Set<java.time.DayOfWeek>, clock: Clock, zone: ZoneId): Instant? {
    if (!alarm.enabled || alarm.firedAt != null) return null
    if (days.isEmpty()) return alarm.occurrence()?.resolveInstant(zone)?.takeIf { it.isAfter(clock.instant()) }
    val after = maxOf(clock.instant(), alarm.fallbackAttemptAt?.let(Instant::ofEpochMilli) ?: Instant.MIN)
    val date = LocalDateTime.ofInstant(after, zone).toLocalDate()
    return (0L..7L).map { date.plusDays(it).atTime(LocalTime.of(alarm.hour, alarm.minute)) }
        .firstOrNull { it.dayOfWeek in days && it.atZone(zone).toInstant().isAfter(after) }
        ?.atZone(zone)?.toInstant()
}

/** A successful write cannot acknowledge an edit made while BLE was in flight. Consumption stays persisted. */
internal fun alarmStateAfterSuccessfulSync(current: AlarmState, sent: AlarmState): AlarmState =
    if (current == sent) current.copy(dirty = false) else current

internal fun alarmIsArmed(enabled: Boolean, firedAt: Long?): Boolean = enabled && firedAt == null
