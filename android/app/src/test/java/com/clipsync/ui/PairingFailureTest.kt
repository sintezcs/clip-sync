package com.clipsync.ui

import com.clipsync.net.PairingApi
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class PairingFailureTest {
    @Test fun unauthorizedAndRateLimitExplainFreshSessionRecovery() {
        assertTrue(pairingFailure(PairingApi.PairingException("private server text", 401)).suggestion!!.contains("fresh link"))
        assertTrue(pairingFailure(PairingApi.PairingException("private server text", 429)).suggestion!!.contains("Wait a minute"))
    }
    @Test fun timeoutExplainsLocalVpnRouteWithoutLeakingRawError() {
        val error = pairingFailure(SocketTimeoutException("private network detail"))
        assertEquals("Mac is unreachable", error.summary)
        assertTrue(error.suggestion!!.contains("VPN"))
        assertNull(error.detail)
    }
    @Test fun pinMismatchNeverSuggestsBypassingIdentityCheck() {
        val error = pairingFailure(SSLPeerUnverifiedException("private error"))
        assertEquals("Certificate mismatch", error.summary)
        assertTrue(error.suggestion!!.contains("complete fingerprint"))
        assertNull(error.detail)
        assertEquals("Secure connection failed", pairingFailure(SSLHandshakeException("private error")).summary)
    }
}
