package com.clipsync.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.clipsync.discovery.Discovered
import com.clipsync.discovery.DiscoveryEvent
import com.clipsync.discovery.NsdDiscovery
import com.clipsync.net.PairingApi
import android.content.Intent
import android.provider.Settings
import com.clipsync.service.ClipForegroundService
import com.clipsync.shizuku.ShizukuClipboardManager
import com.clipsync.storage.Prefs
import com.clipsync.model.AppError
import com.clipsync.model.ErrorAction
import com.clipsync.model.ErrorSeverity
import com.clipsync.util.L
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class TailscaleState {
    data object Unknown : TailscaleState()
    data object NotInstalled : TailscaleState()
    data object Installed : TailscaleState()
}

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data class Connected(val host: String) : ConnectionStatus()
    data class Paused(val host: String) : ConnectionStatus()
    data class Error(val reason: String) : ConnectionStatus()
}

data class SettingsState(
    val mode: String = Prefs.MODE_AUTO,
    val discovered: List<Discovered> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val hasPairing: Boolean = false,
    val pairedHost: String? = null,
    val pairedName: String? = null,
    val pairedPort: Int = Prefs.DEFAULT_PORT,
    val syncEnabled: Boolean = true,
    val autoSendEnabled: Boolean = true,
    val mediaPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val shizukuState: String = "not_checked",
    val tailscaleState: TailscaleState = TailscaleState.Unknown,
    val isOnMobileData: Boolean = false,
    val isOnWifi: Boolean = false,
    val isTailscaleVpnActive: Boolean = false,
    val pairingInProgress: Boolean = false,
    val errors: List<AppError> = emptyList()
)

class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    // Secrets live only in this ViewModel's short-lived session, never SavedState or preferences.
    val pairingCode = MutableStateFlow("")
    private var pairingExpiry: Job? = null
    fun updatePairingCode(code: String) {
        pairingCode.value = code.take(6)
        if (pairingExpiry == null && code.isNotEmpty()) {
            pairingExpiry = viewModelScope.launch {
                delay(120_000)
                clearPairingCode()
            }
        }
    }
    fun clearPairingCode() {
        pairingCode.value = ""
        pairingExpiry?.cancel()
        pairingExpiry = null
    }

    private var bootstrapped = false
    private var discoveryJob: Job? = null
    private var networkWatchJob: Job? = null

    private fun addError(error: AppError) {
        val current = _state.value.errors + error
        _state.value = _state.value.copy(errors = current.takeLast(10))
    }

    fun dismissError(id: String) {
        _state.value = _state.value.copy(errors = _state.value.errors.filter { it.id != id })
    }

    fun bootstrap(context: Context) {
        if (bootstrapped) { refreshOnResume(context); return }
        try {
            bootstrapped = true
            val prefs = Prefs(context)
            val paired = prefs.hasPairing()

            if (paired) L.event(M, "bootstrap hasPairing=true host=${prefs.host} syncEnabled=${prefs.syncEnabled}")
            else L.event(M, "bootstrap hasPairing=false")

            _state.value = _state.value.copy(
                mode = prefs.mode,
                syncEnabled = prefs.syncEnabled,
                autoSendEnabled = prefs.autoSendEnabled,
                mediaPermissionGranted = hasMediaPermission(context),
                notificationPermissionGranted = hasNotificationPermission(context),
                hasPairing = paired,
                pairedHost = prefs.host,
                pairedName = prefs.peerName,
                pairedPort = prefs.port,
                status = if (paired) ConnectionStatus.Connecting else ConnectionStatus.Disconnected
            )
            if (!paired) startDiscovery(context)
            refreshShizukuState(context)
            refreshTailscaleState(context)
            startNetworkWatch(context)

            viewModelScope.launch {
                ClipForegroundService.serviceState.collect { svcState ->
                    val newStatus = when (svcState) {
                        is ClipForegroundService.ServiceState.Disconnected -> ConnectionStatus.Disconnected
                        is ClipForegroundService.ServiceState.Connecting -> ConnectionStatus.Connecting
                        is ClipForegroundService.ServiceState.Connected -> ConnectionStatus.Connected(svcState.host)
                        is ClipForegroundService.ServiceState.Paused -> ConnectionStatus.Paused(svcState.host)
                    }
                    _state.value = _state.value.copy(status = newStatus, syncEnabled = prefs.syncEnabled, pairedHost = prefs.host)


                }
            }
        } catch (t: Throwable) {
            bootstrapped = false
            L.error(M, "bootstrap failed reading prefs", t)
            addError(AppError(
                severity = ErrorSeverity.ERROR,
                summary = "Startup failed",
                detail = t.stackTraceToString().take(500),
                suggestion = "Restart the app. If the problem persists, try clearing app data.",
                action = ErrorAction.Retry,
            ))
        }
    }

    fun setMode(context: Context, mode: String) {
        L.action(M, "setMode mode=$mode")
        Prefs(context).mode = mode
        _state.value = _state.value.copy(mode = mode)
        if (discoveryJob?.isActive != true) startDiscovery(context)
    }

    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        L.action(M, "setAutoSendEnabled enabled=$enabled")
        val prefs = Prefs(context)
        prefs.autoSendEnabled = enabled
        _state.value = _state.value.copy(autoSendEnabled = enabled)
        if (prefs.hasPairing() && prefs.syncEnabled) ClipForegroundService.refreshNotification(context)
    }

    fun startSync(context: Context) {
        L.action(M, "startSync")
        val prefs = Prefs(context)
        val host = prefs.host ?: ""
        if (!prefs.hasPairing()) return

        prefs.syncEnabled = true
        _state.value = _state.value.copy(syncEnabled = true, status = ConnectionStatus.Connecting)
        ClipForegroundService.start(context)

    }

    fun stopSync(context: Context) {
        L.action(M, "stopSync")
        val prefs = Prefs(context)
        prefs.syncEnabled = false
        _state.value = _state.value.copy(syncEnabled = false, status = ConnectionStatus.Disconnected)
        ClipForegroundService.stop(context)
    }

    fun unpair(context: Context) {
        L.action(M, "unpair host=${_state.value.pairedHost}")
        Prefs(context).clearPairing()
        ClipForegroundService.stop(context)
        _state.value = _state.value.copy(
            hasPairing = false,
            pairedHost = null,
            pairedName = null,
            pairedPort = Prefs.DEFAULT_PORT,
            syncEnabled = false,
            status = ConnectionStatus.Disconnected,
            errors = emptyList()
        )
        startDiscovery(context.applicationContext)
    }

    fun startDiscovery(context: Context) {
        discoveryJob?.cancel()
        val nsd = NsdDiscovery(context)
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                nsd.discover().collect { event ->
                    when (event) {
                        is DiscoveryEvent.Found -> {
                            val d = event.info
                            val isNew = _state.value.discovered.none { it.name == d.name }
                            if (isNew) L.event(M, "discovery found name=${d.name} host=${d.host}:${d.port}")
                            val merged = (_state.value.discovered + d).distinctBy { it.name }
                            _state.value = _state.value.copy(discovered = merged)
                        }
                        is DiscoveryEvent.Lost -> {
                            val filtered = _state.value.discovered.filter { it.name != event.name }
                            _state.value = _state.value.copy(discovered = filtered)
                            L.event(M, "discovery lost name=${event.name}")
                        }
                        is DiscoveryEvent.Error -> {
                            L.warn(M, "discovery error: ${event.message}")
                            addError(AppError(
                                severity = ErrorSeverity.WARNING,
                                summary = "Can't find servers on network",
                                detail = event.message,
                                suggestion = "Check that both devices are on the same Wi-Fi network.",
                            ))
                        }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                L.warn(M, "discovery crashed: ${t.message}")
                addError(AppError(
                    severity = ErrorSeverity.WARNING,
                    summary = "Network discovery interrupted",
                    detail = t.message,
                    suggestion = "Discovery will restart on next network change.",
                ))
                if (_state.value.status is ConnectionStatus.Disconnected) {
                    delay(5000)
                    startDiscovery(context)
                }
            }
        }
    }

    fun pair(context: Context, target: PairingTarget, code: String, verifiedFingerprint: String, replaceExisting: Boolean = false) {
        if (_state.value.pairingInProgress) return
        if (Prefs(context).hasPairing() && !replaceExisting) return
        if (!verifiedFingerprint.matches(Regex("[A-Za-z0-9_-]{43}")) || !code.matches(Regex("[0-9]{6}"))) return
        val targetLabel = when (target) {
            is PairingTarget.Auto -> "${target.discovered.host}:${target.discovered.port}"
            is PairingTarget.Manual -> "${target.host}:${target.port}"
        }
        L.action(M, "pair target=$targetLabel")

        _state.value = _state.value.copy(pairingInProgress = true)
        viewModelScope.launch {
            _state.value = _state.value.copy(status = ConnectionStatus.Connecting, errors = emptyList())
            try {
                val prefs = Prefs(context)
                val api = PairingApi()

                val host = when (target) { is PairingTarget.Auto -> target.discovered.host; is PairingTarget.Manual -> target.host }
                val port = when (target) { is PairingTarget.Auto -> target.discovered.port; is PairingTarget.Manual -> target.port }
                val response = withContext(Dispatchers.IO) {
                    api.pairWithKnownFp(host, port, code, verifiedFingerprint)
                }
                persistAndStart(context, prefs, host, port, response.token, verifiedFingerprint,
                    response.secret, if (target is PairingTarget.Auto) Prefs.MODE_AUTO else Prefs.MODE_MANUAL,
                    (target as? PairingTarget.Auto)?.discovered?.name)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                L.error(M, "pair failed", t)
                val appError = when {
                    t is javax.net.ssl.SSLHandshakeException ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Certificate mismatch",
                            detail = t.message,
                            suggestion = "Stop and compare the fingerprint directly on your trusted Mac before pairing again.",
                            action = ErrorAction.Repair,
                        )
                    t is java.net.ConnectException ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Server unreachable",
                            detail = t.message,
                            suggestion = "Check that both devices are on the same network.",
                            action = ErrorAction.Retry,
                        )
                    t is com.clipsync.net.PairingApi.PairingException && t.message?.contains("\"invalid\"") == true ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Wrong pairing code",
                            detail = "The code was incorrect.",
                            suggestion = "Check the code shown on Mac and try again.",
                            action = ErrorAction.Retry,
                        )
                    t is com.clipsync.net.PairingApi.PairingException && t.message?.contains("\"expired\"") == true ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Pairing code expired",
                            detail = "The code was only valid for 5 minutes.",
                            suggestion = "Generate a new code on the Mac.",
                            action = ErrorAction.Retry,
                        )
                    t is com.clipsync.net.PairingApi.PairingException && t.message?.contains("\"consumed\"") == true ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Code already used",
                            detail = "Each pairing code can only be used once.",
                            suggestion = "Generate a new code on the Mac.",
                            action = ErrorAction.Retry,
                        )
                    t is com.clipsync.net.PairingApi.PairingException && t.message?.contains("\"notStarted\"") == true ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "No pairing session on Mac",
                            detail = "The Mac app is not waiting for a pairing request.",
                            suggestion = "Click 'Pair new device' on the Mac first.",
                            action = ErrorAction.Retry,
                        )
                    t is com.clipsync.net.PairingApi.PairingException ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Pairing failed",
                            detail = t.message ?: "Unknown error",
                            suggestion = "Try again.",
                            action = ErrorAction.Retry,
                        )
                    else ->
                        AppError(
                            severity = ErrorSeverity.ERROR,
                            summary = "Connection failed",
                            detail = t.message ?: "Unknown error",
                            suggestion = "Try again.",
                            action = ErrorAction.Retry,
                        )
                }
                _state.value = _state.value.copy(
                    status = ConnectionStatus.Error(appError.summary)
                )
                addError(appError)
            } finally {
                _state.value = _state.value.copy(pairingInProgress = false)
            }
        }
    }

    private suspend fun persistAndStart(
        context: Context,
        prefs: Prefs,
        host: String,
        port: Int,
        token: String,
        fp: String,
        pairingSecret: String,
        mode: String,
        peerName: String?
    ) {
        L.action(M, "pairSuccess host=$host port=$port mode=$mode")
        withContext(Dispatchers.IO) {
            prefs.savePairing(host, port, token, fp, pairingSecret, mode, peerName)
        }
        discoveryJob?.cancel()
        _state.value = _state.value.copy(
            syncEnabled = true,
            hasPairing = true,
            pairedHost = host,
            pairedName = peerName,
            pairedPort = port,
            mode = mode,
            status = ConnectionStatus.Connecting,
            errors = emptyList()
        )
        ClipForegroundService.start(context)
    }

    fun refreshShizukuState(context: Context) {
        val installed = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        val state = if (!installed) {
            "not_installed"
        } else if (!Shizuku.pingBinder()) {
            "not_running"
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            "no_permission"
        } else {
            "ready"
        }
        val prev = _state.value.shizukuState
        if (state != prev) L.perm(M, "shizuku state=$state prev=$prev")
        _state.value = _state.value.copy(shizukuState = state)
    }

    fun refreshOnResume(context: Context) {
        val prefs = Prefs(context)
        val mediaGranted = hasMediaPermission(context)
        val notifGranted = hasNotificationPermission(context)
        _state.value = _state.value.copy(
            mediaPermissionGranted = mediaGranted,
            notificationPermissionGranted = notifGranted,
            syncEnabled = prefs.syncEnabled,
            hasPairing = prefs.hasPairing(),
            pairedHost = prefs.host,
            pairedName = prefs.peerName,
        )
        refreshShizukuState(context)
        refreshTailscaleState(context)
    }

    fun onMediaPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(mediaPermissionGranted = granted)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(notificationPermissionGranted = granted)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasMediaPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestShizukuPermission() {
        L.action(M, "requestShizukuPermission")
        try {
            if (!Shizuku.pingBinder()) return
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == ShizukuClipboardManager.PERMISSION_REQUEST_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        L.perm(M, "shizukuPermissionResult granted=$granted")
                        _state.value = _state.value.copy(
                            shizukuState = if (granted) "ready" else "no_permission"
                        )
                        Shizuku.removeRequestPermissionResultListener(this)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(ShizukuClipboardManager.PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            L.warn(M, "requestShizukuPermission failed: ${e.message}")
            addError(AppError(
                severity = ErrorSeverity.WARNING,
                summary = "Shizuku permission request failed",
                detail = e.message,
                suggestion = "Make sure Shizuku is running. Open Shizuku app and start the service.",
            ))
        }
    }

    private fun startNetworkWatch(context: Context) {
        networkWatchJob?.cancel()
        networkWatchJob = viewModelScope.launch {
            val appContext = context.applicationContext
            while (true) {
                delay(3_000)
                val onWifi = isOnWifi(appContext)
                val onMobile = isOnMobileData(appContext)
                val vpnActive = withContext(Dispatchers.IO) { isTailscaleVpnActive(appContext) }
                val prev = _state.value

                if (onWifi != prev.isOnWifi || onMobile != prev.isOnMobileData || vpnActive != prev.isTailscaleVpnActive) {
                    L.event(M, "network changed: wifi=$onWifi mobile=$onMobile vpn=$vpnActive")
                    _state.value = prev.copy(
                        isOnWifi = onWifi,
                        isOnMobileData = onMobile,
                        isTailscaleVpnActive = vpnActive,
                    )
                    if (!_state.value.hasPairing) startDiscovery(appContext)
                }
            }
        }
    }

    fun refreshTailscaleState(context: Context) {
        val packages = listOf("com.tailscale.ipn", "com.tailscale.ipn.fdroid")
        val installed = packages.any { pkg ->
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        val onMobile = isOnMobileData(context)
        val onWifi = isOnWifi(context)
        val vpnActive = isTailscaleVpnActive(context)
        L.event(M, "tailscale check: installed=$installed onMobile=$onMobile onWifi=$onWifi vpnActive=$vpnActive")
        _state.value = _state.value.copy(
            tailscaleState = if (installed) TailscaleState.Installed else TailscaleState.NotInstalled,
            isOnMobileData = onMobile,
            isOnWifi = onWifi,
            isTailscaleVpnActive = vpnActive,
        )
    }

    fun openTailscalePlayStore(context: Context) {
        L.action(M, "openTailscalePlayStore")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
                    .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            )
        }
    }

    private fun isOnMobileData(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isTailscaleHost(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    @Suppress("DEPRECATION")
    private fun isTailscaleVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val tailscaleUid = try {
            context.packageManager.getApplicationInfo("com.tailscale.ipn", 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            val ni = cm.getNetworkInfo(network) ?: return@any false
            val extra = ni.extraInfo ?: return@any false
            extra.contains("com.tailscale.ipn")
        }
    }

    companion object {
        private const val M = "VM"
    }
}
