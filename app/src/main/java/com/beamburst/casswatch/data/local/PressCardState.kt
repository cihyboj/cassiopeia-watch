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

data class ToggleCardState(val enabled: Boolean = false)

data class ShortPressCards(
    val broadcast: BroadcastCardState,
    val flashlight: ToggleCardState,
)

data class LongPressCards(
    val broadcast: BroadcastCardState,
    val findPhone: ToggleCardState,
)
