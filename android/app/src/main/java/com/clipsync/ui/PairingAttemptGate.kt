package com.clipsync.ui

/** Serializes credential commits with later explicit pause/remove actions. */
internal class PairingAttemptGate {
    private var current: Any? = null

    @Synchronized fun begin(): Any = Any().also { current = it }
    @Synchronized fun isCurrent(attempt: Any): Boolean = current === attempt

    @Synchronized fun runIfCurrent(attempt: Any, action: () -> Unit): Boolean {
        if (current !== attempt) return false
        action()
        return true
    }

    @Synchronized fun invalidate(action: () -> Unit = {}) {
        current = null
        action()
    }
}
