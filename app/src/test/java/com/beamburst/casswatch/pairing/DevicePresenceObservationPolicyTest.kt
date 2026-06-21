package com.beamburst.casswatch.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePresenceObservationPolicyTest {

    @Test
    fun `api below 31 uses fallback only`() {
        assertEquals(
            DevicePresenceObservationMode.FALLBACK_ONLY,
            DevicePresenceObservationPolicy.modeForSdk(30),
        )
    }

    @Test
    fun `api 31 through 35 uses address based observation`() {
        assertEquals(
            DevicePresenceObservationMode.ADDRESS_BASED,
            DevicePresenceObservationPolicy.modeForSdk(31),
        )
        assertEquals(
            DevicePresenceObservationMode.ADDRESS_BASED,
            DevicePresenceObservationPolicy.modeForSdk(35),
        )
    }

    @Test
    fun `api 36 and newer uses request based observation`() {
        assertEquals(
            DevicePresenceObservationMode.REQUEST_BASED,
            DevicePresenceObservationPolicy.modeForSdk(36),
        )
    }
}
