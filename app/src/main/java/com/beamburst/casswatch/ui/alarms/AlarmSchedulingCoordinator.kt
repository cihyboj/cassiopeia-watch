package com.beamburst.casswatch.ui.alarms

import android.content.Context
import com.beamburst.casswatch.data.local.MultiWatchMigrator
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncHistoryStore
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface AlarmSchedulingCoordinator {
    val unavailable: StateFlow<Set<AlarmKey>>
    suspend fun beginWrite(address: String, pending: AlarmWritePending)
    suspend fun finishWrite(address: String, pending: AlarmWritePending, transform: (AlarmState) -> AlarmState)
    suspend fun reconcile(address: String)
    suspend fun reconcileAll()
    suspend fun cancelWatch(address: String)
    suspend fun snapshot(address: String): AlarmState
    suspend fun update(address: String, transform: (AlarmState) -> AlarmState): AlarmState?
    suspend fun forgetWatch(address: String, remove: suspend () -> Unit)
    suspend fun replaceAll(restore: suspend () -> Unit)
    suspend fun fallback(key: AlarmKey, instant: Long, hash: String, handoff: (AlarmSyncStorage.StoredAlarm) -> Unit): Boolean
    suspend fun <T> serialized(block: suspend () -> T): T
}

internal interface AlarmPersistence {
    suspend fun initialize() = Unit
    suspend fun slots(address: String): Set<Int> = (0..5).toSet()
    suspend fun addresses(): List<String>
    suspend fun load(address: String): AlarmState
    suspend fun save(address: String, state: AlarmState)
    suspend fun history(address: String): List<SyncEntry>
    suspend fun legacySlots(): Set<Int>
    suspend fun migrationDone(): Boolean
    suspend fun finishMigration()
}

internal interface AlarmScheduleBackend {
    fun recovery(address: String, instant: Long) = Unit
    fun cancel(key: AlarmKey)
    fun cancelFallback(key: AlarmKey)
    fun cancelLegacy(slot: Int)
    fun expiry(key: AlarmKey, instant: Long?)
    fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability
}

/** All persisted decisions, migration, callback validation, edits and removal share this mutex. */
internal class PersistedAlarmSchedulingCoordinator(
    private val persistence: AlarmPersistence,
    private val backend: AlarmScheduleBackend,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : AlarmSchedulingCoordinator {
    private val mutex = Mutex()
    private val unavailableKeys = MutableStateFlow<Set<AlarmKey>>(emptySet())
    override val unavailable = unavailableKeys.asStateFlow()
    private var initialized = false
    override suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock {
        if (!initialized) {
            migrateLocked()
            initialized = true
        }
        block()
    }

    private suspend fun migrateLocked() {
        persistence.initialize()
        if (persistence.migrationDone()) return
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        persistence.addresses().forEach { reconcileLocked(it) }
        // A crash anywhere above leaves the migration retryable; cancellation and rebuilding are idempotent.
        persistence.finishMigration()
    }

    override suspend fun beginWrite(address: String, pending: AlarmWritePending) {
        update(address) { it.copy(writePending = pending) }
    }

    override suspend fun finishWrite(address: String, pending: AlarmWritePending, transform: (AlarmState) -> AlarmState) {
        update(address) { if (it.writePending == pending) transform(it).copy(writePending = null) else it }
    }

    override suspend fun reconcile(address: String) = serialized {
        reconcileLocked(address)
    }
    override suspend fun reconcileAll() = serialized {
        persistence.addresses().forEach { reconcileLocked(it) }
    }
    override suspend fun cancelWatch(address: String) = serialized { cancelLocked(address) }
    override suspend fun snapshot(address: String): AlarmState = serialized {
        val normalized = AlarmKey.from(address, 0)?.normalizedAddress ?: return@serialized AlarmState()
        reconcileLocked(normalized)
        if (normalized in persistence.addresses()) persistence.load(normalized) else AlarmState()
    }
    override suspend fun update(address: String, transform: (AlarmState) -> AlarmState): AlarmState? = serialized {
        val normalized = AlarmKey.from(address, 0)?.normalizedAddress ?: return@serialized null
        if (normalized !in persistence.addresses()) return@serialized null
        val next = transform(persistence.load(normalized))
        persistence.save(normalized, next)
        reconcileLocked(normalized)
        persistence.load(normalized)
    }
    override suspend fun forgetWatch(address: String, remove: suspend () -> Unit) = serialized {
        cancelLocked(address)
        val key = AlarmKey.from(address, 0)
        if (key != null) persistence.save(key.normalizedAddress, AlarmState())
        remove()
    }
    override suspend fun replaceAll(restore: suspend () -> Unit) = serialized {
        persistence.addresses().forEach { cancelLocked(it) }
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        initialized = false
        try {
            restore()
        } catch (error: Exception) {
            persistence.addresses().forEach { reconcileLocked(it) }
            throw error
        }
        // Import may replace the multi-watch migration flag and contain global legacy keys.
        persistence.initialize()
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        // Ignore an imported device's migration flag: this installation must rebuild.
        persistence.addresses().forEach { reconcileLocked(it) }
        persistence.finishMigration()
        initialized = true
    }
    override suspend fun fallback(key: AlarmKey, instant: Long, hash: String, handoff: (AlarmSyncStorage.StoredAlarm) -> Unit): Boolean = serialized {
        val addresses = persistence.addresses()
        if (key.normalizedAddress !in addresses) return@serialized false
        val state = persistence.load(key.normalizedAddress)
        if (state.writePending != null) {
            reconcileLocked(key.normalizedAddress)
            return@serialized false
        }
        if (!validFallbackCallback(key, addresses, state, instant, hash, clock, zone())) {
            // Late, duplicate and obsolete broadcasts still advance recurring schedules.
            reconcileLocked(key.normalizedAddress)
            return@serialized false
        }
        val alarm = state.alarms[key.slot]
        if (watchHasOccurrence(key, alarm, instant, state.confirmedPayloads(), clock.millis(), zone())) {
            reconcileLocked(key.normalizedAddress)
            return@serialized false
        }
        suspend fun persistStatus(status: String, requested: Boolean = false) {
            persistence.save(key.normalizedAddress, state.copy(alarms = state.alarms.mapIndexed { slot, old ->
                if (slot == key.slot) old.copy(fallbackAttemptAt = if (requested) instant else null,
                    fallbackStatus = status, fallbackStatusAt = instant) else old
            }))
        }
        // Pending is explicitly unconfirmed, not a delivery receipt. A crash remains visible.
        persistStatus("pending")
        try {
            // Storage/migration can suspend until after the due time. Check at the actual hand-off.
            if (!validFallbackCallback(key, addresses, state, instant, hash, clock, zone())) {
                persistStatus("failed")
                return@serialized false
            }
            handoff(alarm)
            persistStatus("requested", requested = true)
            true
        } catch (error: Exception) {
            // Do not turn a failed startActivity into a successful attempt receipt.
            if (error !is kotlinx.coroutines.CancellationException) persistStatus("failed")
            throw error
        } finally {
            reconcileLocked(key.normalizedAddress)
        }
    }

    private suspend fun cancelLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        persistence.slots(key.normalizedAddress).forEach { backend.cancel(key.copy(slot = it)) }
        unavailableKeys.value = unavailableKeys.value.filterNot { it.normalizedAddress == key.normalizedAddress }.toSet()
    }

    private suspend fun reconcileLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        if (key.normalizedAddress !in persistence.addresses()) return
        val old = persistence.load(key.normalizedAddress)
        val state = reconcileAlarmState(old, clock, zone())
        if (old != state) persistence.save(key.normalizedAddress, state)
        state.writePending?.let { backend.recovery(key.normalizedAddress, it.deadline) }
        val unavailable = unavailableKeys.value.filterNot { it.normalizedAddress == key.normalizedAddress }.toMutableSet()
        state.alarms.forEachIndexed { slot, alarm ->
            val alarmKey = key.copy(slot = slot)
            val days = state.days[slot] ?: AlarmSchedulePlanner.AllDays
            backend.expiry(alarmKey, if (days.isEmpty() && alarm.enabled && alarm.firedAt == null)
                alarm.occurrence()?.resolveInstant(zone())?.toEpochMilli() else null)
            val instant = nextAlarmOccurrence(alarm, days, clock, zone())?.toEpochMilli()
            if (state.writePending != null || instant == null || alarm.fallbackAttemptAt == instant ||
                (alarm.fallbackStatus == "failed" && alarm.fallbackStatusAt == instant) || watchHasOccurrence(alarmKey, alarm, instant, state.confirmedPayloads(), clock.millis(), zone())) {
                backend.cancelFallback(alarmKey)
            } else if (backend.schedule(alarmKey, alarm, instant) == FallbackAvailability.UNAVAILABLE) {
                backend.cancelFallback(alarmKey)
                unavailable += alarmKey
            }
        }
        unavailableKeys.value = unavailable
    }
}

@Singleton
internal class AndroidAlarmPersistence @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val historyStore: SyncHistoryStore,
) : AlarmPersistence {
    private val migrationFlag = ALARM_FALLBACK_MIGRATION_FLAG
    override suspend fun initialize() = MultiWatchMigrator.migrateIfNeeded(context)
    override suspend fun slots(address: String): Set<Int> {
        // Cancellation must survive even an undecodable state. Include sparse legacy slots.
        val entries = LocalDataStorage.toJsonObject(context)
        val suffix = "_${com.beamburst.casswatch.utils.Utils.sanitizeMacAddress(address)}"
        val own = org.json.JSONObject()
        entries.keys().asSequence().filter { it.endsWith(suffix) }.forEach { own.put(it, entries.get(it)) }
        return (0..5).toSet() + persistedAlarmSlots(own)
    }
    override suspend fun addresses(): List<String> = LocalDataStorage.getDeviceAddresses(context)
    override suspend fun load(address: String): AlarmState = AlarmSyncStorage.loadRecovering(context, address)
    override suspend fun save(address: String, state: AlarmState) = AlarmSyncStorage.saveState(context, address, state)
    override suspend fun history(address: String): List<SyncEntry> = historyStore.load(address)
    override suspend fun migrationDone(): Boolean = LocalDataStorage.get(context, migrationFlag, "false").toBoolean()
    override suspend fun finishMigration() = LocalDataStorage.putAsync(context, migrationFlag, "true")
    override suspend fun legacySlots(): Set<Int> = persistedAlarmSlots(LocalDataStorage.toJsonObject(context))
}

fun interface AlarmZoneProvider {
    fun currentZone(): ZoneId
}

@Singleton
class AlarmSchedulingTime @Inject constructor(
    val clock: Clock,
    private val zones: AlarmZoneProvider,
) {
    fun zone(): ZoneId = zones.currentZone()
}

@Module
@InstallIn(SingletonComponent::class)
internal object AlarmSchedulingModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun zones(): AlarmZoneProvider = AlarmZoneProvider(ZoneId::systemDefault)

    @Provides
    @Singleton
    fun coordinator(persistence: AndroidAlarmPersistence, scheduler: PhoneFallbackScheduler, time: AlarmSchedulingTime): AlarmSchedulingCoordinator =
        PersistedAlarmSchedulingCoordinator(persistence, scheduler, time.clock, time::zone)
}
