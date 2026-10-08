package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.Settings

object WatchSettingsCache {
    private val gson = Gson()
    private val writeMutex = Mutex()

    fun cacheKey(addr: String) = "WatchSettings_$addr"
    fun dirtyKey(addr: String) = "WatchSettings_${addr}_dirty"

    suspend fun save(context: Context, addr: String, settings: Settings) {
        writeMutex.withLock {
            LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(settings))
            LocalDataStorage.putAsync(context, dirtyKey(addr), "true")
        }
    }

    suspend fun saveClean(context: Context, addr: String, settings: Settings) {
        writeMutex.withLock {
            LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(settings))
            LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
        }
    }

    fun load(context: Context, addr: String): Settings? {
        val json = LocalDataStorage.get(context, cacheKey(addr)) ?: return null
        return runCatching { gson.fromJson(json, Settings::class.java) }.getOrNull()
    }

    fun isDirty(context: Context, addr: String): Boolean =
        LocalDataStorage.get(context, dirtyKey(addr), "false")?.toBoolean() ?: false

    suspend fun clearDirty(context: Context, addr: String) {
        writeMutex.withLock {
            LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
        }
    }

    suspend fun delete(context: Context, addr: String) {
        writeMutex.withLock {
            LocalDataStorage.deleteAsync(context, cacheKey(addr))
            LocalDataStorage.deleteAsync(context, dirtyKey(addr))
        }
    }

    suspend fun reconcileOnConnect(context: Context, addr: String, api: IGShockAPI) {
        writeMutex.withLock {
            if (isDirty(context, addr)) {
                val cached = load(context, addr)
                if (cached != null) {
                    runCatching { api.setSettings(cached) }
                        .onSuccess {
                            LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
                        }
                } else {
                    val fromWatch = runCatching { api.getSettings() }.getOrNull()
                    if (fromWatch != null) {
                        LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(fromWatch))
                    }
                    LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
                }
            } else {
                if (load(context, addr) != null) return

                val fromWatch = runCatching { api.getSettings() }.getOrNull()
                if (fromWatch != null) {
                    LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(fromWatch))
                    LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
                }
            }
        }
    }
}
