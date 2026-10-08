package com.beamburst.casswatch.pairing

enum class DevicePresenceObservationMode {
    FALLBACK_ONLY,
    ADDRESS_BASED,
    REQUEST_BASED,
}

object DevicePresenceObservationPolicy {
    private const val API_ADDRESS_BASED_PRESENCE = 31
    private const val API_REQUEST_BASED_PRESENCE = 36

    fun modeForSdk(sdkInt: Int): DevicePresenceObservationMode =
        when {
            sdkInt >= API_REQUEST_BASED_PRESENCE -> DevicePresenceObservationMode.REQUEST_BASED
            sdkInt >= API_ADDRESS_BASED_PRESENCE -> DevicePresenceObservationMode.ADDRESS_BASED
            else -> DevicePresenceObservationMode.FALLBACK_ONLY
        }
}
