package com.beamburst.casswatch.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncOnReconnectResolutionTest {

    @Test
    fun `stored true wins`() {
        assertTrue(LocalDataStorage.resolveAutoSyncOnReconnect(stored = true, seed = false))
    }

    @Test
    fun `stored false wins`() {
        assertFalse(LocalDataStorage.resolveAutoSyncOnReconnect(stored = false, seed = true))
    }

    @Test
    fun `missing value uses false seed`() {
        assertFalse(LocalDataStorage.resolveAutoSyncOnReconnect(stored = null, seed = false))
    }

    @Test
    fun `missing value uses true seed`() {
        assertTrue(LocalDataStorage.resolveAutoSyncOnReconnect(stored = null, seed = true))
    }

    @Test
    fun `missing value and missing seed defaults true`() {
        assertTrue(LocalDataStorage.resolveAutoSyncOnReconnect(stored = null, seed = null))
    }
}
