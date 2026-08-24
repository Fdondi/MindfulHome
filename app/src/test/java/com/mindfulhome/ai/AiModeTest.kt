package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModeTest {

    @Test
    fun fromStored_defaultsToBackend() {
        assertEquals(AiMode.BACKEND, AiMode.fromStored(null))
        assertEquals(AiMode.BACKEND, AiMode.fromStored("bogus"))
        assertEquals(AiMode.ON_DEVICE, AiMode.fromStored("on_device"))
        assertEquals(AiMode.NONE, AiMode.fromStored("none"))
        assertEquals(AiMode.BACKEND, AiMode.fromStored("backend"))
    }

    @Test
    fun usesBackendAndOnDevice_areExclusive() {
        assertTrue(AiMode.BACKEND.usesBackend)
        assertFalse(AiMode.BACKEND.usesOnDevice)
        assertTrue(AiMode.ON_DEVICE.usesOnDevice)
        assertFalse(AiMode.ON_DEVICE.usesBackend)
        assertFalse(AiMode.NONE.usesBackend)
        assertFalse(AiMode.NONE.usesOnDevice)
    }
}
