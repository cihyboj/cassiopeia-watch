package com.beamburst.casswatch.data.local

enum class ExtraType { STRING, INT, BOOLEAN }

data class BroadcastExtra(
    val key: String,
    val type: ExtraType,
    val value: String,
)

data class BroadcastCardState(
    val enabled: Boolean = false,
    val extras: List<BroadcastExtra> = emptyList(),
)

enum class FindPhoneMelody { ALARM, RINGTONE, NOTIFICATION }

data class FindPhoneCardState(
    val enabled: Boolean = false,
    val volumePercent: Int = 100,
    val melody: FindPhoneMelody = FindPhoneMelody.ALARM,
)

data class ShortPressCards(
    val broadcast: BroadcastCardState,
)

data class LongPressCards(
    val broadcast: BroadcastCardState,
    val findPhone: FindPhoneCardState,
)
