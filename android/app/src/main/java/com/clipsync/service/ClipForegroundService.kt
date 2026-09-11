package com.clipsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.clipsync.app.R
import com.clipsync.clipboard.ClipboardWriter
import com.clipsync.discovery.DiscoveryEvent
import com.clipsync.discovery.NsdDiscovery
import com.clipsync.images.ImageCache
import com.clipsync.images.ImageSafety
import com.clipsync.model.ClipPayload
import com.clipsync.model.ClipPayloadBuilder
import com.clipsync.net.ClipClient
import com.clipsync.net.NetworkChangeObserver
import com.clipsync.net.PairingApi
import com.clipsync.notifications.IncomingClipNotifier
import com.clipsync.overlay.ClipSender
import com.clipsync.shizuku.ClipboardSnapshot
import com.clipsync.shizuku.ShizukuClipboardManager
import com.clipsync.storage.Prefs
import com.clipsync.sync.ClipboardEvents
import com.clipsync.sync.ClipboardOrder
import com.clipsync.sync.LatestOutbox
import com.clipsync.sync.PeerOperation
import com.clipsync.sync.ServiceStartGate
import com.clipsync.util.L
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket

/** Connection ownership and a single clipboard reducer live here, independent of any Activity. */
class ClipForegroundService : Service() {
    private lateinit var prefs: Prefs
    private val client = ClipClient()
    private lateinit var imageCache: ImageCache
    private lateinit var notifier: IncomingClipNotifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clipboardLock = Mutex()
    private class PendingClip(val payload: ClipPayload, val peer: PeerOperation)
    private val outbox = LatestOutbox<PendingClip>()
    private data class Inbound(val payload: ClipPayload, val generation: Long, val token: String, val pin: String)
    private val incoming = Channel<Inbound>(Channel.CONFLATED)
    private var senderJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryJob: Job? = null
    private var pollJob: Job? = null
    private var ws: WebSocket? = null
    private var generation = 0L
    private var backoffMs = 1000L
    private var connectedHost: String? = null
    private var observer: NetworkChangeObserver? = null
    private var helper: ShizukuClipboardManager? = null
    private val order = ClipboardOrder()
    @Volatile private var destroyed = false
    private var pairedIdentity: Pair<String?, String?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        imageCache = ImageCache(this)
        notifier = IncomingClipNotifier(this, imageCache)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW))
        notifier.ensureChannel()
        startForeground(NOTIF_ID, notification("Starting…"))
        startGate.created()
        scope.launch(Dispatchers.IO) { runCatching { imageCache.cleanupOlderThan() } }
        observer = NetworkChangeObserver(this) {
            scope.launch {
                // Endpoint availability changes; the paired fingerprint, token and secret never do.
                disconnect()
                backoffMs = 1000L
                startDiscovery(restart = true)
                connect()
            }
        }.also { it.register() }
        helper = ShizukuClipboardManager(this).also { manager ->
            manager.onStateChanged = { state -> scope.launch { helperState(state) } }
            manager.initialize()
            helperState(manager.state)
        }
        scope.launch {
            for (event in incoming) {
                if (generation == event.generation && prefs.token == event.token && prefs.fp == event.pin) {
                    clipboardLock.withLock { receive(event.payload, event.generation, PeerOperation(event.pin, event.token)) }
                }
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                clipboardLock.withLock { observeClipboard() }
                flushOutbox()
                delay(750)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.hasPairing()) { stopSelf(); return START_NOT_STICKY }
        val identity = prefs.fp to prefs.token
        if (pairedIdentity != identity) {
            pairedIdentity = identity
            clearOutbox()
            order.resetPeer()
            _readiness.value = _readiness.value.copy(lastReceivedAt = null)
            startDiscovery(restart = true)
        }
        if (!prefs.syncEnabled) clearOutbox()
        if (intent?.action == ACTION_REFRESH_NOTIF && connectedHost != null) {
            publishConnection()
        } else {
            startDiscovery()
            connect()
        }
        return START_STICKY
    }

    private fun helperState(state: ShizukuClipboardManager.State) {
        val running = state == ShizukuClipboardManager.State.READY
        _readiness.value = _readiness.value.copy(
            helperAuthorized = running || state == ShizukuClipboardManager.State.BINDING,
            helperRunning = running,
            clipboardReadable = if (running) _readiness.value.clipboardReadable else false,
            lastIssue = if (running) null else "Clipboard helper unavailable"
        )
        if (!running) { order.resetBaseline(); clearOutbox() }
        publishConnection()
    }

    /** NSD advertises candidates only. Probe with the SAVED pin and commit endpoint only after authenticated WS open. */
    private fun startDiscovery(restart: Boolean = false) {
        if (prefs.mode != Prefs.MODE_AUTO || !prefs.hasPairing()) { discoveryJob?.cancel(); discoveryJob = null; return }
        if (!restart && discoveryJob?.isActive == true) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive) {
                try {
                    NsdDiscovery(this@ClipForegroundService).discover().collect { event ->
                        if (event !is DiscoveryEvent.Found || connectedHost != null) return@collect
                        val savedPin = prefs.fp ?: return@collect
                        val peerToken = prefs.token
                        val candidate = event.info
                        // TXT fingerprint is untrusted and cannot replace savedPin.
                        val valid = withContext(Dispatchers.IO) { PairingApi().ping(candidate.host, candidate.port, savedPin).isSuccess }
                        if (valid && prefs.fp == savedPin && prefs.token == peerToken && prefs.mode == Prefs.MODE_AUTO && connectedHost == null) {
                            connect(candidate.host, candidate.port)
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { issue("Local discovery unavailable; retrying") }
                delay(5000)
            }
        }
    }

    private fun connect(host: String? = prefs.host, port: Int = prefs.port) {
        if (destroyed || !prefs.hasPairing()) return
        if (host.isNullOrBlank()) { scheduleReconnect(); return }
        val pin = prefs.fp ?: return
        val token = prefs.token ?: return
        reconnectJob?.cancel()
        generation++
        val expected = generation
        ws?.cancel()
        connectedHost = null
        _readiness.value = _readiness.value.copy(networkConnected = false)
        _serviceState.value = ServiceState.Connecting
        updateNotification("Connecting…")
        try {
            ws = client.connectWebSocket(client.pinnedClient(host, pin), host, port, token,
                onFrame = { payload -> incoming.trySend(Inbound(payload, expected, token, pin)); Unit },
                onStatus = { status -> scope.launch {
                    if (generation != expected || prefs.token != token || prefs.fp != pin) return@launch
                    when (status) {
                        is ClipClient.WsStatus.Open -> {
                            connectedHost = host
                            prefs.host = host
                            prefs.port = port
                            backoffMs = 1000L
                            _readiness.value = _readiness.value.copy(networkConnected = true)
                            publishConnection()
                            flushOutbox()
                        }
                        is ClipClient.WsStatus.Closed, is ClipClient.WsStatus.Error -> {
                            disconnect()
                            scheduleReconnect()
                        }
                    }
                } })
        } catch (_: Exception) {
            disconnect()
            scheduleReconnect()
        }
    }

    private fun disconnect() {
        generation++
        ws?.cancel()
        ws = null
        connectedHost = null
        _readiness.value = _readiness.value.copy(networkConnected = false)
        _serviceState.value = ServiceState.Disconnected
        updateNotification("Disconnected · reconnecting…")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            // Clear reference before connect() cancels the scheduled job.
            reconnectJob = null
            connect()
        }
    }

    private suspend fun readSnapshot(manager: ShizukuClipboardManager): ClipboardSnapshot? {
        return try {
            withContext(Dispatchers.IO) { manager.getClipboardSnapshot() }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            clearOutbox()
            _readiness.value = _readiness.value.copy(clipboardReadable = false)
            issue("Clipboard cannot be read; check helper access")
            publishConnection()
            null
        }
    }

    private fun capturePeer(): PeerOperation? {
        if (!prefs.hasTrustedIdentity()) return null
        val pin = prefs.fp ?: return null
        val token = prefs.token ?: return null
        return PeerOperation(pin, token)
    }

    private fun PeerOperation.isCurrent(): Boolean =
        !destroyed && isCurrent(prefs.fp, prefs.token, prefs.hasTrustedIdentity())

    /** Initial clipboard is a baseline, never an upload. No registration/debounce/echo time windows. */
    private suspend fun observeClipboard(): ClipboardSnapshot? {
        val operation = capturePeer() ?: return null
        val manager = helper ?: return null
        if (!manager.isAvailable()) return null
        val snapshot = readSnapshot(manager)
        if (!operation.isCurrent()) return null
        val wasReadable = _readiness.value.clipboardReadable
        _readiness.value = _readiness.value.copy(clipboardReadable = snapshot != null)
        if (wasReadable != (snapshot != null)) publishConnection()
        if (snapshot == null) { clearOutbox(); issue("Clipboard cannot be read; use explicit Share"); return null }
        if (!order.observe(snapshot.identity, System.currentTimeMillis())) return snapshot
        clearOutbox() // any newer clipboard item supersedes queued content, even unsupported or sensitive.
        if (!prefs.syncEnabled || !prefs.autoSendEnabled || !prefs.hasPairing() || snapshot.sensitive) return snapshot
        try {
            val payload = when {
                snapshot.mime == "text/plain" && snapshot.text != null -> ClipPayloadBuilder.text(snapshot.text)
                snapshot.mime in com.clipsync.images.ClipboardImageConverter.INPUT_MIMES -> {
                    val image = withContext(Dispatchers.IO) {
                        com.clipsync.images.ClipboardImageConverter.prepare(manager.getClipboardImage(snapshot), requireNotNull(snapshot.mime))
                    }
                    // A slow provider must not enqueue an image after a newer user copy.
                    val current = readSnapshot(manager)
                    if (current?.identity != snapshot.identity) return snapshot
                    ClipPayloadBuilder.image(image.mime, image.bytes)
                }
                else -> return snapshot
            }
            if (!operation.isCurrent() || !prefs.syncEnabled || !prefs.autoSendEnabled) return null
            outbox.offer(PendingClip(payload, operation), SystemClock.elapsedRealtime())
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: com.clipsync.images.ClipboardImageOutputTooLarge) {
            issue("Converted PNG exceeds 50 MiB; copy a smaller image")
        }
        catch (_: Exception) { issue("Clipboard image unavailable; use explicit Share") }
        return snapshot
    }

    private fun clearOutbox() {
        outbox.clear()
        senderJob?.cancel()
        senderJob = null
    }

    private fun flushOutbox() {
        if (senderJob?.isActive == true || connectedHost == null || !prefs.syncEnabled || !prefs.autoSendEnabled) return
        val entry = outbox.take(SystemClock.elapsedRealtime()) ?: return
        if (!entry.value.peer.isCurrent()) return
        val host = connectedHost ?: return
        val port = prefs.port
        val pin = prefs.fp ?: return
        val token = prefs.token ?: return
        val secret = prefs.pairingSecret ?: return
        senderJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                ClipSender(client).sendCancellable(host, port, token, secret, pin, entry.value.payload,
                    isCurrent = { entry.value.peer.isCurrent() && prefs.syncEnabled && prefs.autoSendEnabled && prefs.token == token && prefs.fp == pin && outbox.current(entry, SystemClock.elapsedRealtime()) })
            }
            if (result is ClipSender.Result.Failed) issue("Send not confirmed; copy again when connected")
            else _readiness.value = _readiness.value.copy(lastIssue = null)
            senderJob = null
            flushOutbox()
        }
    }

    private suspend fun receive(payload: ClipPayload, expectedGeneration: Long, operation: PeerOperation) {
        if (!operation.isCurrent() || generation != expectedGeneration || !prefs.syncEnabled || payload.type !in setOf("text", "image")) return
        val accepted = try {
            withContext(Dispatchers.IO) { ClipboardEvents.shouldAcceptInbound(payload, operation.fingerprint) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            issue("Replay protection unavailable; clipboard update blocked")
            return
        }
        if (!accepted || !operation.isCurrent()) return // durable acceptance precedes EVERY clipboard effect
        val baseline = observeClipboard()
        // Legacy wire has no origin/session/sequence. This conservatively rejects older timestamps;
        // absolute cross-device ordering cannot be guaranteed with independent device clocks.
        if (!operation.isCurrent() || generation != expectedGeneration || !prefs.syncEnabled || !order.canApply(payload.ts)) return
        val manager = helper
        try {
            if (payload.type == "text") {
                val text = String(java.util.Base64.getDecoder().decode(payload.data), Charsets.UTF_8)
                if (manager?.isAvailable() == true && baseline != null) {
                    val current = readSnapshot(manager)
                    if (current?.identity != baseline.identity) return
                }
                if (!operation.isCurrent() || generation != expectedGeneration || !prefs.syncEnabled || !order.acceptRemote(payload.ts)) return
                clearOutbox()
                ClipboardWriter.writeText(this, text, payload.nonce)
                recordReceived()
                if (manager?.isAvailable() == true) acknowledgeOwnWrite(manager, payload.nonce, operation)
            } else {
                val uri = withContext(Dispatchers.IO) {
                    val bytes = Base64.decode(payload.data, Base64.DEFAULT)
                    ImageSafety.validate(bytes, payload.mime)
                    imageCache.writeImage(bytes, IncomingClipNotifier.extensionForMime(payload.mime))
                }
                if (manager?.isAvailable() == true && baseline != null) {
                    val current = readSnapshot(manager)
                    if (current?.identity != baseline.identity) return
                }
                if (!operation.isCurrent() || generation != expectedGeneration || !prefs.syncEnabled || !order.acceptRemote(payload.ts)) return
                clearOutbox()
                ClipboardWriter.writeImage(this, uri, payload.mime, payload.nonce)
                recordReceived()
                if (manager?.isAvailable() == true) acknowledgeOwnWrite(manager, payload.nonce, operation)
            }
            if (operation.isCurrent() && generation == expectedGeneration && prefs.syncEnabled) withContext(Dispatchers.IO) { notifier.notify(payload) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { issue("Incoming clipboard could not be applied") }
    }

    private suspend fun acknowledgeOwnWrite(manager: ShizukuClipboardManager, nonce: String, operation: PeerOperation) {
        val after = readSnapshot(manager)
        if (!operation.isCurrent()) return
        // Never label a racing USER copy as our remote write, including a same-content recopy.
        if (after == null || !order.acknowledge(nonce, after.sourceEvent, after.identity)) {
            issue("Clipboard write not confirmed or superseded by a local copy")
            observeClipboard()
        }
    }

    private fun recordReceived() {
        _readiness.value = _readiness.value.copy(lastReceivedAt = System.currentTimeMillis())
    }

    private fun publishConnection() {
        val host = connectedHost ?: return
        _serviceState.value = if (prefs.syncEnabled) ServiceState.Connected(host) else ServiceState.Paused(host)
        val ready = _readiness.value
        updateNotification(when {
            !prefs.syncEnabled -> "Sync paused"
            !ready.helperRunning || !ready.clipboardReadable -> "Connected · receiving only; clipboard setup needed"
            !prefs.autoSendEnabled -> "Connected · automatic sending off"
            else -> "Connected · clipboard ready"
        })
    }
    private fun issue(message: String) {
        if (_readiness.value.lastIssue != message) {
            _readiness.value = _readiness.value.copy(lastIssue = message)
            L.warn("SVC", message)
        }
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ClipSync").setContentText(text).setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    private fun updateNotification(text: String) {
        if (!destroyed) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
    override fun onDestroy() {
        startGate.destroyed()
        destroyed = true
        generation++
        observer?.unregister()
        clearOutbox()
        incoming.close()
        scope.cancel()
        helper?.destroy()
        ws?.cancel()
        _serviceState.value = ServiceState.Disconnected
        _readiness.value = Readiness()
        super.onDestroy()
    }
    data class Readiness(
        val networkConnected: Boolean = false,
        val helperAuthorized: Boolean = false,
        val helperRunning: Boolean = false,
        val clipboardReadable: Boolean = false,
        val lastIssue: String? = null,
        val lastReceivedAt: Long? = null
    )
    sealed class ServiceState {
        data object Disconnected : ServiceState()
        data object Connecting : ServiceState()
        data class Connected(val host: String) : ServiceState()
        data class Paused(val host: String) : ServiceState()
    }
    companion object {
        private val startGate = ServiceStartGate()
        private const val CHANNEL_ID = "clipsync_sync"
        private const val NOTIF_ID = 4242
        const val ACTION_REFRESH_NOTIF = "com.clipsync.REFRESH_NOTIF"
        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Disconnected)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
        private val _readiness = MutableStateFlow(Readiness())
        val readiness: StateFlow<Readiness> = _readiness.asStateFlow()
        fun ensureRunning(context: Context) {
            if (!startGate.requestStart()) return
            try { start(context) }
            catch (error: Exception) { startGate.startFailed(); throw error }
        }
        fun start(context: Context) { context.startForegroundService(Intent(context, ClipForegroundService::class.java)) }
        fun stop(context: Context) {
            context.stopService(Intent(context, ClipForegroundService::class.java))
            // A pending start can be cancelled before onCreate/onDestroy ever run.
            startGate.startFailed()
        }
        fun refreshNotification(context: Context) {
            context.startForegroundService(Intent(context, ClipForegroundService::class.java).setAction(ACTION_REFRESH_NOTIF))
        }
    }
}
