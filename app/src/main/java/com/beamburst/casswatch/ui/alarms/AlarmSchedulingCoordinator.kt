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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface AlarmSchedulingCoordinator {
    suspend fun reconcile(address: String)
    suspend fun reconcileAll()
    suspend fun cancelWatch(address: String)
    /** Hold the same lock through removal, import, edit or BLE snapshot construction. */
    suspend fun <T> serialized(block: suspend () -> T): T
}

internal interface AlarmPersistence {
    suspend fun addresses(): List<String>
    suspend fun load(address: String): AlarmState
    suspend fun save(address: String, state: AlarmState)
    suspend fun history(address: String): List<SyncEntry>
}

internal interface AlarmScheduleBackend {
    fun cancel(key: AlarmKey)
    fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long)
}

/** Decision logic has no Android dependencies. All callers share this one application mutex. */
internal class PersistedAlarmSchedulingCoordinator(
    private val persistence: AlarmPersistence,
    private val backend: AlarmScheduleBackend,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : AlarmSchedulingCoordinator {
    private val mutex = Mutex()
    override suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
    override suspend fun reconcile(address: String) = serialized { reconcileLocked(address) }
    override suspend fun reconcileAll() = serialized {
        persistence.addresses().forEach { reconcileLocked(it) }
    }
    override suspend fun cancelWatch(address: String) = serialized {
        cancelLocked(address)
    }

    internal suspend fun cancelLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        val state = persistence.load(key.normalizedAddress)
        (state.alarms.indices + (0..4) + state.days.keys).distinct().forEach {
            backend.cancel(key.copy(slot = it))
        }
    }

    internal suspend fun reconcileLocked(address: String) {
        val key = AlarmKey.from(address, 0) ?: return
        if (key.normalizedAddress !in persistence.addresses()) return
        val old = persistence.load(key.normalizedAddress)
        val state = reconcileAlarmState(old, clock, zone())
        if (old != state) persistence.save(key.normalizedAddress, state)
        state.alarms.forEachIndexed { slot, alarm ->
            val instant = nextAlarmOccurrence(alarm, state.days[slot] ?: AlarmSchedulePlanner.AllDays, clock, zone())
            if (instant == null) backend.cancel(key.copy(slot = slot))
            else backend.schedule(key.copy(slot = slot), alarm, instant.toEpochMilli())
        }
    }
}

@Singleton
internal class AndroidAlarmPersistence @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val historyStore: SyncHistoryStore,
) : AlarmPersistence {
    override suspend fun addresses(): List<String> = LocalDataStorage.getDeviceAddresses(context)
    override suspend fun load(address: String): AlarmState = AlarmSyncStorage.loadState(context, address)
    override suspend fun save(address: String, state: AlarmState) = AlarmSyncStorage.saveState(context, address, state)
    override suspend fun history(address: String): List<SyncEntry> = historyStore.load(address)
}

@Module
@InstallIn(SingletonComponent::class)
internal object AlarmSchedulingModule {
    @Provides
    @Singleton
    fun coordinator(persistence: AndroidAlarmPersistence, scheduler: PhoneFallbackScheduler): AlarmSchedulingCoordinator =
        PersistedAlarmSchedulingCoordinator(persistence, object : AlarmScheduleBackend {
            override fun cancel(key: AlarmKey) = scheduler.cancel(key.slot)
            override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long) {
                scheduler.schedule(key.slot, alarmHash(alarm.hour, alarm.minute, emptySet(), true), instant,
                    alarm.hour, alarm.minute, alarm.name, key.normalizedAddress)
            }
        }, Clock.systemUTC(), ZoneId::systemDefault)
}
