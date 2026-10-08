package com.beamburst.casswatch.ui.alarms

import android.content.Context
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
    suspend fun addresses(): List<String>
    suspend fun load(address: String): AlarmState
    suspend fun save(address: String, state: AlarmState)
    suspend fun history(address: String): List<SyncEntry>
    suspend fun legacySlots(): Set<Int>
    suspend fun migrationDone(): Boolean
    suspend fun finishMigration()
}

internal interface AlarmScheduleBackend {
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
    override suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun migrateLocked() {
        if (persistence.migrationDone()) return
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        persistence.addresses().forEach { reconcileLocked(it) }
        // A crash anywhere above leaves the migration retryable; cancellation and rebuilding are idempotent.
        persistence.finishMigration()
    }

    override suspend fun reconcile(address: String) = serialized {
        migrateLocked()
        reconcileLocked(address)
    }
    override suspend fun reconcileAll() = serialized {
        migrateLocked()
        persistence.addresses().forEach { reconcileLocked(it) }
    }
    override suspend fun cancelWatch(address: String) = serialized { cancelLocked(address) }
    override suspend fun snapshot(address: String): AlarmState = serialized {
        migrateLocked()
        reconcileLocked(address)
        if (address in persistence.addresses()) persistence.load(address) else AlarmState()
    }
    override suspend fun update(address: String, transform: (AlarmState) -> AlarmState): AlarmState? = serialized {
        migrateLocked()
        if (address !in persistence.addresses()) return@serialized null
        val next = transform(persistence.load(address))
        persistence.save(address, next)
        reconcileLocked(address)
        persistence.load(address)
    }
    override suspend fun forgetWatch(address: String, remove: suspend () -> Unit) = serialized {
        cancelLocked(address)
        remove()
    }
    override suspend fun replaceAll(restore: suspend () -> Unit) = serialized {
        persistence.addresses().forEach { cancelLocked(it) }
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        try {
            restore()
        } catch (error: Exception) {
            persistence.addresses().forEach { reconcileLocked(it) }
            throw error
        }
        ((0..4).toSet() + persistence.legacySlots()).forEach(backend::cancelLegacy)
        // Ignore an imported device's migration flag: this installation must rebuild.
        persistence.addresses().forEach { reconcileLocked(it) }
        persistence.finishMigration()
    }
    override suspend fun fallback(key: AlarmKey, instant: Long, hash: String, handoff: (AlarmSyncStorage.StoredAlarm) -> Unit): Boolean = serialized {
        val state = persistence.load(key.normalizedAddress)
        if (!validFallbackCallback(key, persistence.addresses(), state, instant, hash, clock, zone())) return@serialized false
        val alarm = state.alarms[key.slot]
        val confirmed = watchHasOccurrence(key, alarm, instant, persistence.history(key.normalizedAddress), clock.millis(), zone())
        // Persist before hand-off: a repeated callback must not create another clock alarm.
        persistence.save(key.normalizedAddress, state.copy(alarms = state.alarms.mapIndexed { slot, old ->
            if (slot == key.slot) old.copy(fallbackAttemptAt = instant) else old
        }))
        try {
            if (!confirmed) handoff(alarm)
        } finally {
            reconcileLocked(key.normalizedAddress)
        }
        !confirmed
    }

    private suspend fun cancelLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        val state = persistence.load(key.normalizedAddress)
        (state.alarms.indices + (0..4) + state.days.keys).distinct().forEach { backend.cancel(key.copy(slot = it)) }
        unavailableKeys.value = unavailableKeys.value.filterNot { it.normalizedAddress == key.normalizedAddress }.toSet()
    }

    private suspend fun reconcileLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        if (key.normalizedAddress !in persistence.addresses()) return
        val old = persistence.load(key.normalizedAddress)
        val state = reconcileAlarmState(old, clock, zone())
        if (old != state) persistence.save(key.normalizedAddress, state)
        val history = persistence.history(key.normalizedAddress)
        val unavailable = unavailableKeys.value.filterNot { it.normalizedAddress == key.normalizedAddress }.toMutableSet()
        state.alarms.forEachIndexed { slot, alarm ->
            val alarmKey = key.copy(slot = slot)
            val days = state.days[slot] ?: AlarmSchedulePlanner.AllDays
            backend.expiry(alarmKey, if (days.isEmpty() && alarm.enabled && alarm.firedAt == null)
                alarm.occurrence()?.resolveInstant(zone())?.toEpochMilli() else null)
            val instant = nextAlarmOccurrence(alarm, days, clock, zone())?.toEpochMilli()
            if (instant == null || alarm.fallbackAttemptAt == instant || watchHasOccurrence(alarmKey, alarm, instant, history, clock.millis(), zone())) {
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
    private val migrationFlag = "AlarmFallbackIdentityMigrated"
    override suspend fun addresses(): List<String> = LocalDataStorage.getDeviceAddresses(context)
    override suspend fun load(address: String): AlarmState = AlarmSyncStorage.loadState(context, address)
    override suspend fun save(address: String, state: AlarmState) = AlarmSyncStorage.saveState(context, address, state)
    override suspend fun history(address: String): List<SyncEntry> = historyStore.load(address)
    override suspend fun migrationDone(): Boolean = LocalDataStorage.get(context, migrationFlag, "false").toBoolean()
    override suspend fun finishMigration() = LocalDataStorage.putAsync(context, migrationFlag, "true")
    override suspend fun legacySlots(): Set<Int> = persistedAlarmSlots(LocalDataStorage.toJsonObject(context))
}

@Singleton
class AlarmSchedulingTime @Inject constructor() {
    val clock: Clock = Clock.systemUTC()
    fun zone(): ZoneId = ZoneId.systemDefault()
}

@Module
@InstallIn(SingletonComponent::class)
internal object AlarmSchedulingModule {
    @Provides
    @Singleton
    fun coordinator(persistence: AndroidAlarmPersistence, scheduler: PhoneFallbackScheduler, time: AlarmSchedulingTime): AlarmSchedulingCoordinator =
        PersistedAlarmSchedulingCoordinator(persistence, scheduler, time.clock, time::zone)
}
