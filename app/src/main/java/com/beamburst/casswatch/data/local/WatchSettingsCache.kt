package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.google.gson.Gson
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.Settings

object WatchSettingsCache {
    private val gson = Gson()

    fun cacheKey(addr: String) = "WatchSettings_$addr"
    fun dirtyKey(addr: String) = "WatchSettings_${addr}_dirty"

    fun save(context: Context, addr: String, settings: Settings) {
        LocalDataStorage.put(context, cacheKey(addr), gson.toJson(settings))
        LocalDataStorage.put(context, dirtyKey(addr), "true")
    }

    fun saveClean(context: Context, addr: String, settings: Settings) {
        LocalDataStorage.put(context, cacheKey(addr), gson.toJson(settings))
        clearDirty(context, addr)
    }

    fun load(context: Context, addr: String): Settings? {
        val json = LocalDataStorage.get(context, cacheKey(addr)) ?: return null
        return runCatching { gson.fromJson(json, Settings::class.java) }.getOrNull()
    }

    fun isDirty(context: Context, addr: String): Boolean =
        LocalDataStorage.get(context, dirtyKey(addr), "false")?.toBoolean() ?: false

    fun clearDirty(context: Context, addr: String) {
        LocalDataStorage.put(context, dirtyKey(addr), "false")
    }

    fun delete(context: Context, addr: String) {
        LocalDataStorage.delete(context, cacheKey(addr))
        LocalDataStorage.delete(context, dirtyKey(addr))
    }

    suspend fun reconcileOnConnect(context: Context, addr: String, api: IGShockAPI) {
        if (isDirty(context, addr)) {
            val cached = load(context, addr)
            if (cached != null) {
                runCatching { api.setSettings(cached) }
                    .onSuccess { clearDirty(context, addr) }
            }
        } else {
            val fromWatch = runCatching { api.getSettings() }.getOrNull()
            if (fromWatch != null) {
                saveClean(context, addr, fromWatch)
            }
        }
    }
}
