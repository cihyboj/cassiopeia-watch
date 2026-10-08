package com.beamburst.casswatch.ui.alarms

import CachedIO
import android.content.Context
import com.beamburst.casswatch.R
import com.beamburst.casswatch.pairing.watchOperationError
import com.beamburst.casswatch.pairing.awaitWatchResponse
import com.beamburst.casswatch.pairing.WATCH_ALARMS_TIMEOUT_MS
import com.beamburst.casswatch.pairing.WATCH_RESPONSE_TIMEOUT_MS
import kotlinx.coroutines.withTimeout
import java.util.UUID
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.pairing.WatchConnectionChangedException
import com.beamburst.casswatch.utils.SyncHistoryLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
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
    snapshot: AlarmState,
    clock: Clock,
    zone: ZoneId,
    writeAlarms: suspend (List<Alarm>) -> Unit,
) {
    val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
    if (WatchInfo.chimeInSettings) {
        val current = api.getSettings()
        if (current.hourlyChime != target) {
            api.setSettings(current.copy(hourlyChime = target))
        }
    } else {
        val current = api.getAlarms()
        val payload = hourlySignalAlarmPayload(current, target, snapshot.firedAts().keys, snapshot.oneShotOccurrences(), clock, zone)
        if (payload != current) writeAlarms(payload)
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

/** Every alarm-register writer shares fresh readback and persisted bounded in-flight state. */
internal data class AlarmPayloadRecord(
    val address: String,
    val payload: List<Alarm>,
    val trigger: SyncTrigger,
    val target: Boolean,
    val success: Boolean,
    val error: String? = null,
)

@Singleton
class AlarmPayloadWriter internal constructor(
    private val coordinator: AlarmSchedulingCoordinator,
    private val time: AlarmSchedulingTime,
    private val session: WatchConnectionSession,
    private val record: suspend (AlarmPayloadRecord) -> Unit,
) {
    @Inject constructor(
        coordinator: AlarmSchedulingCoordinator,
        time: AlarmSchedulingTime,
        session: WatchConnectionSession,
        logger: SyncHistoryLogger,
    ) : this(coordinator, time, session, { entry ->
        logger.record(address = entry.address, trigger = entry.trigger, kinds = alarmSyncHistoryKinds,
            success = entry.success, errorMessage = entry.error,
            details = alarmSyncDetailsForWatchPayload(entry.payload, entry.target))
    })
    suspend fun write(
        ready: WatchConnectionSession.Ready,
        payload: List<Alarm>,
        snapshot: AlarmState,
        trigger: SyncTrigger,
        target: Boolean,
        confirm: suspend () -> Unit,
        readBack: suspend () -> List<Alarm>,
        send: suspend (List<Alarm>) -> Unit,
    ) {
        confirm()
        checkSession(ready)
        val pending = AlarmWritePending(UUID.randomUUID().toString(), time.clock.millis() + ALARM_WRITE_TIMEOUT_MS)
        var actual = payload
        try {
            withTimeout(ALARM_WRITE_TIMEOUT_MS) {
                coordinator.beginWrite(ready.address, pending)
                checkSession(ready)
                actual = payload.mapIndexed { slot, alarm ->
                    if (slot in snapshot.firedAts() || snapshot.oneShotOccurrences()[slot]?.isElapsed(time.clock, time.zone()) == true)
                        alarm.copy(enabled = false) else alarm
                }
                send(actual)
                confirm()
                checkSession(ready)
                // AlarmsIO.request uses this cache key. Remove it after queuing SETs,
                // immediately before GETs, under the guarded generation's I/O mutex.
                CachedIO.remove("GET_ALARMS")
                val received = awaitWatchResponse(WATCH_ALARMS_TIMEOUT_MS) { readBack() }
                confirm()
                checkSession(ready)
                if (!alarmRegistersMatch(actual, received)) {
                    throw AlarmConfirmationException("Alarm readback did not match the sent registers")
                }
                val syncZone = time.zone()
                val evidence = watchPayloadsAfterWrite(ready.address, actual, time.clock, syncZone)
                coordinator.finishWrite(ready.address, pending) { current ->
                    checkSession(ready)
                    current.copy(watchPayloads = evidence, confirmedWriteId = pending.id).clearFallbackStatuses()
                }
                confirm()
                checkSession(ready)
                record(AlarmPayloadRecord(ready.address, actual, trigger, target, true))
            }
        } catch (error: Exception) {
            // Disconnect cancels generation work. Await cleanup/history outside that
            // cancellation; the persisted deadline also recovers a killed process.
            withContext(NonCancellable) {
                withTimeout(WATCH_RESPONSE_TIMEOUT_MS) {
                    coordinator.update(ready.address) { current ->
                        if (current.writePending == pending ||
                            current.confirmedWriteId == pending.id) {
                            current.copy(watchPayloads = emptyMap(), confirmedWriteId = null, writePending = null, dirty = true)
                        } else current
                    }
                    record(AlarmPayloadRecord(ready.address, actual, trigger, target, false,
                        "Alarm registers unconfirmed: ${error.message ?: error.javaClass.simpleName}"))
                }
            }
            throw error
        }
    }

    private fun checkSession(ready: WatchConnectionSession.Ready) {
        if (!session.isCurrent(ready)) throw WatchConnectionChangedException()
    }

    suspend fun hourlySignal(
        api: IGShockAPI,
        ready: WatchConnectionSession.Ready,
        settings: HourlySignalSettings,
        now: LocalDateTime,
        trigger: SyncTrigger,
        confirm: suspend () -> Unit,
    ) {
        val snapshot = coordinator.snapshot(ready.address)
        if (snapshot.alarms.isNotEmpty() && snapshot.confirmedPayloads().isEmpty()) {
            // Send Time also repairs lost alarm evidence even when the chime is unchanged
            // or lives in settings rather than alarm registers.
            val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
            val scheduled = AlarmSchedulePlanner.applyWeeklySchedule(snapshot.watchAlarms(), snapshot.days, now,
                AlarmViewMode.WEEKLY, snapshot.firedAts(), snapshot.oneShotLocalDates(), time.zone(), time.clock)
            val payload = if (WatchInfo.chimeInSettings) scheduled else AlarmSchedulePlanner.mirrorChime(scheduled, target)
            write(ready, payload, snapshot, trigger, target, confirm, { api.getAlarms() }) { api.setAlarms(ArrayList(it)) }
            coordinator.update(ready.address) { current ->
                checkSession(ready)
                alarmStateAfterSuccessfulSync(current, snapshot)
            }
        }
        applyHourlySignalToWatch(api, settings, now, snapshot, time.clock, time.zone()) { payload ->
            write(ready, payload, snapshot, trigger, HourlySignalEvaluator.shouldChimeAt(now, settings), confirm, { api.getAlarms() }) { actual ->
                api.setAlarms(ArrayList(actual))
            }
        }
    }
}

internal fun watchPayloadsAfterWrite(address: String, alarms: List<Alarm>, clock: Clock, zone: ZoneId): Map<Int, WatchAlarmPayload> =
    alarms.mapIndexed { slot, alarm ->
        slot to WatchAlarmPayload(address, alarm.hour, alarm.minute, alarm.enabled, true, zone.id,
            zone.rules.getOffset(clock.instant()).totalSeconds, clock.millis())
    }.toMap()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmPayloadEntryPoint {
    fun alarmPayloadWriter(): AlarmPayloadWriter
}

/** Allows time writers to keep all alarm reconciliation/evidence inside their payload call. */
suspend fun applyReconciledHourlySignal(
    context: Context,
    api: IGShockAPI,
    ready: WatchConnectionSession.Ready,
    settings: HourlySignalSettings,
    now: LocalDateTime,
    trigger: SyncTrigger,
    confirm: suspend () -> Unit,
) = EntryPointAccessors.fromApplication(context.applicationContext, AlarmPayloadEntryPoint::class.java)
    .alarmPayloadWriter().hourlySignal(api, ready, settings, now, trigger, confirm)

internal const val ALARM_WRITE_TIMEOUT_MS = WATCH_ALARMS_TIMEOUT_MS + WATCH_RESPONSE_TIMEOUT_MS

internal fun alarmRegistersMatch(sent: List<Alarm>, received: List<Alarm>, compareChime: Boolean = !WatchInfo.chimeInSettings): Boolean =
    sent.isNotEmpty() && received.size >= sent.size && sent.indices.all { slot ->
        val expected = sent[slot]
        val actual = received[slot]
        expected.hour == actual.hour && expected.minute == actual.minute &&
            expected.enabled == actual.enabled && (!compareChime || expected.hasHourlyChime == actual.hasHourlyChime)
    }

/** Diagnostics belong in history; screen feedback uses the existing translated API error. */
class AlarmConfirmationException(message: String) : IllegalStateException(message)

fun Context.alarmOperationError(error: Throwable?): String =
    if (error is AlarmConfirmationException) getString(R.string.api_error) else watchOperationError(error)
