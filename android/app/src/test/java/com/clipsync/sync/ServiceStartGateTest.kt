package com.clipsync.sync

import org.junit.Assert.*
import org.junit.Test

class ServiceStartGateTest {
    @Test fun `activity resume recreation and new intent do not duplicate pending start`() {
        val gate = ServiceStartGate()
        assertTrue(gate.requestStart())
        assertFalse(gate.requestStart())
        gate.created()
        assertFalse(gate.requestStart())
        assertFalse(gate.requestStart())
    }
    @Test fun `stopped service can restart from restored activity`() {
        val gate = ServiceStartGate()
        gate.created()
        gate.destroyed()
        assertTrue(gate.requestStart())
    }
    @Test fun `failed foreground request can be retried`() {
        val gate = ServiceStartGate()
        assertTrue(gate.requestStart())
        gate.startFailed()
        assertTrue(gate.requestStart())
    }
    @Test fun `cancelling pending start permits retry but cannot restart an existing service`() {
        val gate = ServiceStartGate()
        assertTrue(gate.requestStart())
        gate.startFailed()
        assertTrue(gate.requestStart())
        gate.created()
        gate.startFailed()
        assertFalse(gate.requestStart())
    }
    @Test fun `process recreation does not mistake saved activity state for running service`() {
        val previousProcess = ServiceStartGate()
        previousProcess.created()
        assertTrue(ServiceStartGate().requestStart())
    }
}
