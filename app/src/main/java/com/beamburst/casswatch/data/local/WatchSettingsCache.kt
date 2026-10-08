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

    suspend fun reconcileOnConnect(
        context: Context,
        addr: String,
        api: IGShockAPI,
        checkSession: () -> Unit,
    ) {
        writeMutex.withLock {
            reconcileConnectedSettings(
                api = api,
                checkSession = checkSession,
                cached = load(context, addr),
                dirty = isDirty(context, addr),
                save = { settings -> LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(settings)) },
                clearDirty = { LocalDataStorage.putAsync(context, dirtyKey(addr), "false") },
            )
        }
    }
}

/** Shared seam for reconnect reconciliation; caller supplies storage for the captured address. */
internal suspend fun reconcileConnectedSettings(
    api: IGShockAPI,
    checkSession: () -> Unit,
    cached: Settings?,
    dirty: Boolean,
    save: suspend (Settings) -> Unit,
    clearDirty: suspend () -> Unit,
) {
    checkSession()
    if (dirty && cached != null) {
        runCatching {
            checkSession()
            api.setSettings(cached)
            checkSession()
            clearDirty()
        }
    } else if (cached == null) {
        val fromWatch = runCatching { api.getSettings() }.getOrNull() ?: return
        checkSession()
        save(fromWatch)
        checkSession()
        clearDirty()
    }
}
