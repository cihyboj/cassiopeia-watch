package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PerWatchCacheCleaner {

    fun wipeKeys(addr: String): List<String> {
        val migratedKeys = MultiWatchMigrator.GLOBAL_KEYS.map { "${it}_$addr" }
        val extraKeys = listOf(
            "SyncHistory_$addr",
            "WatchSnapshot_$addr",
            LocalDataStorage.timeWriteLatencyKey(addr),
            LocalDataStorage.autoSyncOnReconnectKey(addr),
            WatchSettingsCache.cacheKey(addr),
            WatchSettingsCache.dirtyKey(addr),
        )
        return migratedKeys + extraKeys
    }

    suspend fun wipe(context: Context, addr: String) {
        withContext(Dispatchers.IO) {
            wipeKeys(addr).forEach { key ->
                LocalDataStorage.deleteAsync(context, key)
            }
        }
    }
}
