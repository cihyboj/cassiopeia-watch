package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.google.gson.Gson

data class WatchSnapshot(
    val timer: Int? = null,
    val battery: Int?,
    val temperature: Int?,
    val homeTime: String?,
    val cachedAt: Long,
) {
    companion object {
        private val gson = Gson()

        private fun key(addr: String) = "WatchSnapshot_$addr"

        fun save(context: Context, addr: String, snapshot: WatchSnapshot) {
            LocalDataStorage.put(context, key(addr), gson.toJson(snapshot))
        }

        fun load(context: Context, addr: String): WatchSnapshot? {
            val json = LocalDataStorage.get(context, key(addr)) ?: return null
            return runCatching { gson.fromJson(json, WatchSnapshot::class.java) }.getOrNull()
        }

        fun delete(context: Context, addr: String) {
            LocalDataStorage.delete(context, key(addr))
        }
    }
}
