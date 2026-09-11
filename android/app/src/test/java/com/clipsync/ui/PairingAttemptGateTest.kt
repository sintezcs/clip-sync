package com.clipsync.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingAttemptGateTest {
    @Test fun delayedPairCannotRestoreRemovedCredentials() = delayedResultAfterAction(remove = true)
    @Test fun delayedPairCannotResumePausedSync() = delayedResultAfterAction(remove = false)

    private fun delayedResultAfterAction(remove: Boolean) {
        val gate = PairingAttemptGate()
        val attempt = gate.begin()
        var credentials: String? = "existing"
        var syncing = true
        var starts = 0
        val response = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> {
                check(response.await(2, TimeUnit.SECONDS))
                val saved = gate.runIfCurrent(attempt) { credentials = "late response"; syncing = true }
                gate.runIfCurrent(attempt) { starts++ }
                saved
            }
            gate.invalidate { if (remove) credentials = null; syncing = false }
            response.countDown()
            assertFalse(pending.get(2, TimeUnit.SECONDS))
            assertEquals(if (remove) null else "existing", credentials)
            assertFalse(syncing)
            assertEquals(0, starts)
        } finally { response.countDown(); worker.shutdownNow() }
    }

    @Test fun removeSerializesBehindAlreadyRunningCommitAndWins() {
        val gate = PairingAttemptGate()
        val attempt = gate.begin()
        val entered = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var credentials: String? = null
        try {
            val committing = workers.submit {
                gate.runIfCurrent(attempt) {
                    entered.countDown()
                    check(finishCommit.await(2, TimeUnit.SECONDS))
                    credentials = "response"
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val removing = workers.submit { gate.invalidate { credentials = null } }
            finishCommit.countDown()
            committing.get(2, TimeUnit.SECONDS)
            removing.get(2, TimeUnit.SECONDS)
            assertNull(credentials)
            assertFalse(gate.isCurrent(attempt))
        } finally { finishCommit.countDown(); workers.shutdownNow() }
    }

    @Test fun removeAfterCommitAlsoBlocksQueuedServiceStartAndOldCompletion() {
        val gate = PairingAttemptGate()
        val old = gate.begin()
        var credentials: String? = null
        assertTrue(gate.runIfCurrent(old) { credentials = "committed" })
        gate.invalidate { credentials = null }
        val replacement = gate.begin()
        assertFalse(gate.runIfCurrent(old) { fail("Old callback must not start sync or reset new progress") })
        assertNull(credentials)
        assertTrue(gate.runIfCurrent(replacement) { credentials = "new explicit attempt" })
    }
}
