package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _changes = MutableStateFlow(0L)

    /** append() is a read-modify-write; several appends land within the same second at
     * connect time (connection log, cache prime, alarm reconcile). */
    private val appendMutex = Mutex()

    fun load(deviceAddress: String): List<SyncEntry> {
        val raw = LocalDataStorage.get(context, key(deviceAddress), null) ?: return emptyList()
        return parseSyncHistory(raw)
    }

    suspend fun append(entry: SyncEntry) {
        appendMutex.withLock {
            val current = load(entry.deviceAddress).toMutableList()
            current.add(0, entry)
            while (current.size > CAPACITY) current.removeAt(current.lastIndex)
            LocalDataStorage.putAsync(context, key(entry.deviceAddress), serializeSyncHistory(current))
        }
        _changes.value = System.currentTimeMillis()
    }

    suspend fun clear(deviceAddress: String) {
        LocalDataStorage.deleteAsync(context, key(deviceAddress))
        _changes.value = System.currentTimeMillis()
    }

    fun observe(deviceAddress: String): Flow<List<SyncEntry>> =
        _changes
            .map { load(deviceAddress) }
            // load() is a blocking DataStore read; keep it off the collector's
            // (typically main) thread.
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    private fun key(address: String) = "SyncHistory_$address"

    companion object {
        private const val CAPACITY = 50

        // Non-Hilt factory for use in BroadcastReceiver (no injection available)
        operator fun invoke(context: Context): SyncHistoryStore =
            SyncHistoryStore(context.applicationContext)
    }
}

internal fun serializeSyncHistory(entries: List<SyncEntry>): String {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("ts", e.timestamp)
            put("addr", e.deviceAddress)
            put("trig", e.trigger.name)
            put("kinds", JSONArray(e.kinds.map { it.name }))
            put("ok", e.success)
            e.errorMessage?.let { put("err", it.take(200)) }
            put("d", JSONObject().apply {
                e.details.alarmHashes?.let { put("aH", JSONArray(it)) }
                e.details.eventCount?.let { put("eC", it) }
                e.details.hourlySignalTarget?.let { put("hT", it) }
                e.details.timeMs?.let { put("tM", it) }
                e.details.connection?.let { put("c", it.take(120)) }
                e.details.actions?.let { put("ac", JSONArray(it.map { action -> action.take(120) })) }
            })
        })
    }
    return arr.toString()
}

internal fun parseSyncHistory(raw: String): List<SyncEntry> = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()
    }
}.getOrDefault(emptyList())

private fun parseEntry(obj: JSONObject): SyncEntry {
    val kinds = mutableSetOf<SyncKind>()
    val kindsArr = obj.getJSONArray("kinds")
    for (j in 0 until kindsArr.length()) {
        runCatching { SyncKind.valueOf(kindsArr.getString(j)) }.getOrNull()?.let { kinds += it }
    }
    val d = obj.optJSONObject("d") ?: JSONObject()
    return SyncEntry(
        timestamp = obj.getLong("ts"),
        deviceAddress = obj.getString("addr"),
        trigger = SyncTrigger.valueOf(obj.getString("trig")),
        kinds = kinds,
        success = obj.getBoolean("ok"),
        errorMessage = obj.optString("err").takeIf { it.isNotEmpty() },
        details = SyncDetails(
            alarmHashes = if (d.has("aH")) {
                val aH = d.getJSONArray("aH"); (0 until aH.length()).map { aH.getString(it) }
            } else null,
            eventCount         = if (d.has("eC")) d.getInt("eC") else null,
            hourlySignalTarget = if (d.has("hT")) d.getBoolean("hT") else null,
            timeMs             = if (d.has("tM")) d.getLong("tM") else null,
            connection         = d.optString("c").takeIf { it.isNotEmpty() },
            actions            = if (d.has("ac")) {
                val ac = d.getJSONArray("ac"); (0 until ac.length()).map { ac.getString(it) }
            } else null,
        )
    )
}
