package com.beamburst.casswatch.ui.alarms

import android.content.Context
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

/** Invalidate before transport I/O: a thrown/cancelled/unknown write cannot retain old truth. */
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
        trigger: SyncTrigger,
        target: Boolean,
        confirm: suspend () -> Unit,
        send: suspend () -> Unit,
    ) {
        confirm()
        coordinator.update(ready.address) { current ->
            checkSession(ready)
            current.copy(watchPayloads = emptyMap())
        }
        val syncZone = time.zone()
        val syncClock = Clock.fixed(time.clock.instant(), syncZone)
        try {
            send()
            confirm()
            val evidence = watchPayloadsAfterWrite(ready.address, payload, syncClock, syncZone)
            coordinator.update(ready.address) { current ->
                checkSession(ready)
                current.copy(watchPayloads = evidence)
            }
            confirm()
            record(AlarmPayloadRecord(ready.address, payload, trigger, target, true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: WatchConnectionChangedException) {
            throw changed
        } catch (error: Exception) {
            confirm()
            record(AlarmPayloadRecord(ready.address, payload, trigger, target, false, error.message))
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
        applyHourlySignalToWatch(api, settings, now, snapshot, time.clock, time.zone()) { payload ->
            write(ready, payload, trigger, HourlySignalEvaluator.shouldChimeAt(now, settings), confirm) {
                api.setAlarms(ArrayList(payload))
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
