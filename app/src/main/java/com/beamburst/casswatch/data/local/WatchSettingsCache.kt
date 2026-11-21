package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.pairing.confirmWatchWrite
import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.IGShockAPI
import com.beamburst.casswatch.ui.settings.mergeSettingsRefresh
import org.avmedia.gshockapi.Settings

/**
 * Per-watch copy of the watch settings.
 *
 * - `cache`    — the settings the app shows; may hold unsent user edits (`dirty`).
 * - `baseline` — the settings as last read from / written to the watch. Comparing `cache`
 *   with `baseline` tells which fields the user edited, so a push overwrites only those
 *   fields and never reverts changes made on the watch (auto-sync hour/minute, chime).
 */
object WatchSettingsCache {
    private val gson = Gson()
    private val writeMutex = Mutex()

    fun cacheKey(addr: String) = "WatchSettings_$addr"
    fun dirtyKey(addr: String) = "WatchSettings_${addr}_dirty"
    fun baselineKey(addr: String) = "WatchSettings_${addr}_baseline"

    /** Stores unsent edits; [baseline] is the watch state the edits were made against. */
    suspend fun save(context: Context, addr: String, settings: Settings, baseline: Settings? = null) {
        writeMutex.withLock {
            LocalDataStorage.putAsync(context, cacheKey(addr), gson.toJson(settings))
            if (baseline != null) LocalDataStorage.putAsync(context, baselineKey(addr), gson.toJson(baseline))
            LocalDataStorage.putAsync(context, dirtyKey(addr), "true")
        }
    }

    suspend fun saveClean(context: Context, addr: String, settings: Settings) {
        writeMutex.withLock { saveCleanLocked(context, addr, settings) }
    }

    private suspend fun saveCleanLocked(context: Context, addr: String, settings: Settings) {
        val json = gson.toJson(settings)
        LocalDataStorage.putAsync(context, cacheKey(addr), json)
        LocalDataStorage.putAsync(context, baselineKey(addr), json)
        LocalDataStorage.putAsync(context, dirtyKey(addr), "false")
    }

    fun load(context: Context, addr: String): Settings? = parse(LocalDataStorage.get(context, cacheKey(addr)))

    fun loadBaseline(context: Context, addr: String): Settings? = parse(LocalDataStorage.get(context, baselineKey(addr)))

    private fun parse(json: String?): Settings? =
        json?.let { runCatching { gson.fromJson(it, Settings::class.java) }.getOrNull() }

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
            LocalDataStorage.deleteAsync(context, baselineKey(addr))
        }
    }

    suspend fun reconcileOnConnect(
        context: Context,
        addr: String,
        api: IGShockAPI,
        checkSession: () -> Unit,
        refresh: Boolean = true,
    ) {
        writeMutex.withLock {
            reconcileConnectedSettings(
                api = api,
                checkSession = checkSession,
                cached = load(context, addr),
                baseline = loadBaseline(context, addr),
                dirty = isDirty(context, addr),
                saveClean = { settings -> saveCleanLocked(context, addr, settings) },
                refresh = refresh,
            )
        }
    }
}

/**
 * Shared seam for reconnect reconciliation; caller supplies storage for the captured address.
 *
 * With [refresh] = false (firmware auto-sync, whose link lasts only ~8-10 s) a clean cache is
 * left alone, so the watch gets no extra reads. Otherwise reads the watch first: pending edits are merged onto fresh values (so fields changed
 * on the watch are not reverted), and GShockAPI rebuilds the 0x11 packet (auto-sync hour and
 * minute, connection timeout) only from bytes read on the current connection.
 */
internal suspend fun reconcileConnectedSettings(
    api: IGShockAPI,
    checkSession: () -> Unit,
    cached: Settings?,
    baseline: Settings?,
    dirty: Boolean,
    saveClean: suspend (Settings) -> Unit,
    refresh: Boolean = true,
) {
    if (!refresh && !dirty && cached != null) return
    val boundApi = api.guardWrites(checkSession)
    checkSession()
    val fresh = boundApi.getSettings()
    checkSession()
    val merged = if (dirty && cached != null) mergePendingEdits(fresh, baseline, cached) else fresh
    if (merged != fresh) {
        boundApi.setSettings(merged)
    }
    confirmWatchWrite(checkSession)
    saveClean(merged)
}

/**
 * Applies the user's pending edits ([cached] vs [baseline]) onto [fresh] watch values.
 * Caches written before the baseline existed keep their editable fields, except the
 * auto-sync switch and minute, which older versions often cached stale; fields without
 * UI (hourly chime, DnD, fine adjustment) always come from the watch.
 */
internal fun mergePendingEdits(fresh: Settings, baseline: Settings?, cached: Settings): Settings =
    if (baseline != null) {
        mergeSettingsRefresh(loaded = fresh, baseline = baseline, draft = cached)
    } else {
        cached.copy(
            hourlyChime = fresh.hourlyChime,
            DnD = fresh.DnD,
            fineAdjustment = fresh.fineAdjustment,
            timeAdjustment = fresh.timeAdjustment,
            adjustmentTimeMinutes = fresh.adjustmentTimeMinutes,
            adjustmentTimeHour = fresh.adjustmentTimeHour,
        )
    }
