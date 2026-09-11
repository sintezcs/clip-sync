package com.clipsync.sync

import com.clipsync.model.ClipPayload
import java.security.MessageDigest

/** Bounded process-local nonce ledger; same content with a new nonce is an intentional new event. */
class EventLedger(
    private val capacity: Int = 4096,
    private val retentionMs: Long = 600_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val seen = LinkedHashMap<String, Long>()
    @Synchronized fun accept(nonce: String): Boolean {
        val now = clockMs()
        seen.entries.removeAll { now - it.value >= retentionMs }
        if (nonce in seen || seen.size >= capacity) return false
        seen[nonce] = now
        return true
    }
}

object ClipboardEvents {
    @Volatile private var store: ReplayStore? = null
    internal fun installStore(store: ReplayStore) { this.store = store }
    private fun activeStore(): ReplayStore = checkNotNull(store) { "Replay protection not initialized" }
    fun recordOutbound(payload: ClipPayload, fingerprint: String) {
        check(activeStore().accept(ReplayKey.of(fingerprint, payload.nonce))) { "Duplicate event or replay ledger full" }
    }
    fun shouldAcceptInbound(payload: ClipPayload, fingerprint: String): Boolean =
        activeStore().accept(ReplayKey.of(fingerprint, payload.nonce))
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Only events never attempted on the network are retained. Legacy Mac cannot acknowledge deduped retries. */
class LatestOutbox<T>(private val lifetimeMs: Long = 15_000) {
    data class Entry<T>(val sequence: Long, val value: T, val expiresAt: Long)
    private var sequence = 0L
    private var pending: Entry<T>? = null
    @Synchronized fun offer(value: T, now: Long): Entry<T> = Entry(++sequence, value, now + lifetimeMs).also { pending = it }
    @Synchronized fun take(now: Long): Entry<T>? = pending.also { pending = null }?.takeIf { now < it.expiresAt }
    @Synchronized fun current(entry: Entry<T>, now: Long): Boolean = entry.sequence == sequence && now < entry.expiresAt
    @Synchronized fun clear() { sequence++; pending = null }
}

/** First callback only establishes baseline. Default-network changes never mutate peer credentials. */
class DefaultNetworkState<T> {
    private var initialized = false
    private var current: T? = null
    fun available(network: T): Boolean {
        val changed = initialized && current != network
        initialized = true
        current = network
        return changed
    }
    fun lost(network: T): Boolean {
        if (current != network) return false
        current = null
        return true
    }
}

/** Local observation order. Platform timestamp is part of identity so intentional recopies are retained. */
class ClipboardOrder {
    var identity: String? = null
        private set
    private var lastLocalMs = Long.MIN_VALUE
    private var lastRemoteMs = Long.MIN_VALUE
    fun observe(nextIdentity: String, nowMs: Long): Boolean {
        val changed = identity != null && identity != nextIdentity
        identity = nextIdentity
        if (changed) lastLocalMs = nowMs
        return changed
    }
    fun canApply(remoteTimestampMs: Long): Boolean = remoteTimestampMs > lastLocalMs && remoteTimestampMs >= lastRemoteMs
    fun acceptRemote(remoteTimestampMs: Long): Boolean {
        if (!canApply(remoteTimestampMs)) return false
        lastRemoteMs = remoteTimestampMs
        return true
    }
    fun acknowledge(expectedNonce: String, observedNonce: String?, observedIdentity: String): Boolean {
        if (observedNonce != expectedNonce) return false
        identity = observedIdentity
        return true
    }
    fun resetBaseline() { identity = null }
    fun resetPeer() { identity = null; lastLocalMs = Long.MIN_VALUE; lastRemoteMs = Long.MIN_VALUE }
}

/** Captured trust for one asynchronous operation; endpoint changes do not change peer identity. */
class PeerOperation(val fingerprint: String, private val token: String) {
    fun isCurrent(fingerprint: String?, token: String?, trusted: Boolean): Boolean =
        trusted && this.fingerprint == fingerprint && this.token == token
}
