package com.sdm.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class EntitlementsTest {
    @After fun reset() { Entitlements.premium = true }

    @Test fun premiumHasFullLimits() {
        Entitlements.premium = true
        assertEquals(16, Entitlements.maxConnections())
        assertEquals(10, Entitlements.maxConcurrent(10))
    }

    @Test fun freeIsCapped() {
        Entitlements.premium = false
        assertEquals(8, Entitlements.maxConnections())
        assertEquals(3, Entitlements.maxConcurrent(10))
        assertEquals(2, Entitlements.maxConcurrent(2))
    }
}
