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
        const val KEY_FLASHLIGHT    = "PressCard.Flashlight.enabled"
        const val KEY_FIND_PHONE    = "PressCard.FindPhone.enabled"
    }

    fun shortPress(): ShortPressCards = ShortPressCards(
        broadcast  = BroadcastCardState(
            enabled = getBool(KEY_SHORT_ENABLED),
            extras  = getExtras(KEY_SHORT_EXTRAS),
        ),
        flashlight = ToggleCardState(enabled = getBool(KEY_FLASHLIGHT)),
    )

    fun longPress(): LongPressCards = LongPressCards(
        broadcast = BroadcastCardState(
            enabled = getBool(KEY_LONG_ENABLED),
            extras  = getExtras(KEY_LONG_EXTRAS),
        ),
        findPhone = ToggleCardState(enabled = getBool(KEY_FIND_PHONE)),
    )

    fun saveShortBroadcast(state: BroadcastCardState) {
        LocalDataStorage.put(context, KEY_SHORT_ENABLED, state.enabled.toString())
        putExtras(KEY_SHORT_EXTRAS, state.extras)
    }

    fun saveLongBroadcast(state: BroadcastCardState) {
        LocalDataStorage.put(context, KEY_LONG_ENABLED, state.enabled.toString())
        putExtras(KEY_LONG_EXTRAS, state.extras)
    }

    fun saveFlashlightEnabled(enabled: Boolean) =
        LocalDataStorage.put(context, KEY_FLASHLIGHT, enabled.toString())

    fun saveFindPhoneEnabled(enabled: Boolean) =
        LocalDataStorage.put(context, KEY_FIND_PHONE, enabled.toString())

    private fun getBool(key: String) =
        LocalDataStorage.get(context, key, "false")?.toBoolean() ?: false

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
