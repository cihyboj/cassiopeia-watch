package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PressCardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    companion object {
        const val KEY_SHORT_ENABLED = "PressCard.Short.enabled"
        const val KEY_SHORT_EXTRAS  = "PressCard.Short.extras"
        const val KEY_LONG_ENABLED  = "PressCard.Long.enabled"
        const val KEY_LONG_EXTRAS   = "PressCard.Long.extras"
        const val KEY_FIND_PHONE    = "PressCard.FindPhone.enabled"
        const val KEY_FIND_PHONE_VOLUME = "PressCard.FindPhone.volume"
        const val KEY_FIND_PHONE_MELODY = "PressCard.FindPhone.melody"
    }

    fun shortPress(): ShortPressCards = ShortPressCards(
        broadcast  = BroadcastCardState(
            enabled = getBool(KEY_SHORT_ENABLED),
            extras  = getExtras(KEY_SHORT_EXTRAS),
        ),
    )

    fun longPress(): LongPressCards = LongPressCards(
        broadcast = BroadcastCardState(
            enabled = getBool(KEY_LONG_ENABLED),
            extras  = getExtras(KEY_LONG_EXTRAS),
        ),
        findPhone = FindPhoneCardState(
            enabled = getBool(KEY_FIND_PHONE),
            volumePercent = getInt(KEY_FIND_PHONE_VOLUME, 100).coerceIn(0, 100),
            melody = runCatching {
                FindPhoneMelody.valueOf(LocalDataStorage.get(context, KEY_FIND_PHONE_MELODY, FindPhoneMelody.ALARM.name)!!)
            }.getOrDefault(FindPhoneMelody.ALARM),
        ),
    )

    fun saveShortBroadcast(state: BroadcastCardState) {
        LocalDataStorage.put(context, KEY_SHORT_ENABLED, state.enabled.toString())
        putExtras(KEY_SHORT_EXTRAS, state.extras)
    }

    fun saveLongBroadcast(state: BroadcastCardState) {
        LocalDataStorage.put(context, KEY_LONG_ENABLED, state.enabled.toString())
        putExtras(KEY_LONG_EXTRAS, state.extras)
    }

    fun saveFindPhoneEnabled(enabled: Boolean) =
        LocalDataStorage.put(context, KEY_FIND_PHONE, enabled.toString())

    fun saveFindPhoneConfig(state: FindPhoneCardState) {
        LocalDataStorage.put(context, KEY_FIND_PHONE, state.enabled.toString())
        LocalDataStorage.put(context, KEY_FIND_PHONE_VOLUME, state.volumePercent.coerceIn(0, 100).toString())
        LocalDataStorage.put(context, KEY_FIND_PHONE_MELODY, state.melody.name)
    }

    private fun getBool(key: String) =
        LocalDataStorage.get(context, key, "false")?.toBoolean() ?: false

    private fun getInt(key: String, default: Int): Int =
        LocalDataStorage.get(context, key, default.toString())?.toIntOrNull() ?: default

    private fun getExtras(key: String): List<BroadcastExtra> {
        val json = LocalDataStorage.get(context, key, "[]") ?: "[]"
        return runCatching {
            gson.fromJson<List<BroadcastExtra>>(
                json, object : TypeToken<List<BroadcastExtra>>() {}.type
            )
        }.getOrDefault(emptyList())
    }

    private fun putExtras(key: String, extras: List<BroadcastExtra>) =
        LocalDataStorage.put(context, key, gson.toJson(extras))
}
