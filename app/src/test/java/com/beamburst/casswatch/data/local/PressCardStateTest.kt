package com.beamburst.casswatch.data.local

import org.junit.Assert.*
import org.junit.Test

class PressCardStateTest {
    @Test fun broadcastCardStateDefaultsToDisabledEmpty() {
        val s = BroadcastCardState()
        assertFalse(s.enabled)
        assertTrue(s.extras.isEmpty())
    }

    @Test fun findPhoneCardStateDefaults() {
        val state = FindPhoneCardState()
        assertFalse(state.enabled)
        assertEquals(100, state.volumePercent)
        assertEquals(FindPhoneMelody.ALARM, state.melody)
    }

    @Test fun extraTypesHaveThreeValues() {
        assertEquals(3, ExtraType.values().size)
    }

    @Test fun broadcastExtraHoldsKeyTypeValue() {
        val ex = BroadcastExtra(key = "mode", type = ExtraType.STRING, value = "quick")
        assertEquals("mode", ex.key)
        assertEquals(ExtraType.STRING, ex.type)
        assertEquals("quick", ex.value)
    }

    @Test fun shortPressCardsAggregatesBothCards() {
        val s = ShortPressCards(
            broadcast  = BroadcastCardState(enabled = true),
        )
        assertTrue(s.broadcast.enabled)
    }

    @Test fun longPressCardsAggregatesBothCards() {
        val l = LongPressCards(
            broadcast = BroadcastCardState(enabled = false),
            findPhone = FindPhoneCardState(enabled = true),
        )
        assertFalse(l.broadcast.enabled)
        assertTrue(l.findPhone.enabled)
    }
}
