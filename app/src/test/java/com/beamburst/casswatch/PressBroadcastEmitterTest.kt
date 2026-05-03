package com.beamburst.casswatch

import com.beamburst.casswatch.data.local.BroadcastExtra
import com.beamburst.casswatch.data.local.ExtraType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressBroadcastEmitterTest {
    @Test
    fun actionForUsesShortAction() {
        assertEquals(BroadcastSpec.Short.action, PressBroadcastEmitter.actionFor(BroadcastSpec.Short))
    }

    @Test
    fun actionForUsesLongAction() {
        assertEquals(BroadcastSpec.Long.action, PressBroadcastEmitter.actionFor(BroadcastSpec.Long))
    }

    @Test
    fun coerceExtraValueEncodesTypedExtras() {
        assertEquals("abc", PressBroadcastEmitter.coerceExtraValue(BroadcastExtra("s", ExtraType.STRING, "abc")))
        assertEquals(42, PressBroadcastEmitter.coerceExtraValue(BroadcastExtra("i", ExtraType.INT, "42")))
        assertTrue(PressBroadcastEmitter.coerceExtraValue(BroadcastExtra("b", ExtraType.BOOLEAN, "true")) as Boolean)
    }

    @Test
    fun coerceExtraValueFallsBackForInvalidTypedExtras() {
        assertEquals(0, PressBroadcastEmitter.coerceExtraValue(BroadcastExtra("i", ExtraType.INT, "nope")))
        assertFalse(PressBroadcastEmitter.coerceExtraValue(BroadcastExtra("b", ExtraType.BOOLEAN, "not_bool")) as Boolean)
    }
}
