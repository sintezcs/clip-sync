package com.clipsync.net

import com.clipsync.sync.DefaultNetworkState
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual reducer used by the Android default-network callback. */
class NetworkChangeObserverTest {
    @Test fun `initial callback and duplicate do not reconnect`() {
        val state = DefaultNetworkState<Int>()
        assertFalse(state.available(1))
        assertFalse(state.available(1))
    }
    @Test fun `default network switch reconnects but old network loss does not`() {
        val state = DefaultNetworkState<Int>()
        state.available(1)
        assertTrue(state.available(2))
        assertFalse(state.lost(1))
        assertFalse(state.available(2))
    }
    @Test fun `loss and restoration both notify`() {
        val state = DefaultNetworkState<Int>()
        state.available(1)
        assertTrue(state.lost(1))
        assertFalse(state.lost(1))
        assertTrue(state.available(1))
    }
}
