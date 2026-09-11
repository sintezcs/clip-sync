package com.clipsync.sync

/** Process-local lifecycle facts, separate from connection/readability state. */
class ServiceStartGate {
    private var running = false
    private var requested = false
    @Synchronized fun requestStart(): Boolean {
        if (running || requested) return false
        requested = true
        return true
    }
    @Synchronized fun created() { running = true; requested = false }
    @Synchronized fun destroyed() { running = false; requested = false }
    @Synchronized fun startFailed() { requested = false }
}
