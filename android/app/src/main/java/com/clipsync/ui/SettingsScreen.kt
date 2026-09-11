package com.clipsync.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import com.clipsync.service.ClipForegroundService
import com.clipsync.storage.Prefs
import com.clipsync.ui.components.*
import com.clipsync.ui.layout.AdaptiveSettingsLayout

sealed class PairingTarget {
    data class Auto(val discovered: com.clipsync.discovery.Discovered) : PairingTarget()
    data class Manual(val host: String, val port: Int) : PairingTarget()
}

enum class SettingsDestination(val title: String) {
    HOME("ClipSync"), CONNECTION("Connection"), CLIPBOARD("Clipboard"), PRIVACY("Privacy"),
    SETUP("Setup & diagnostics"), PAIRING("Review pairing"), REMOVE("Remove paired Mac")
}

@Composable
fun SettingsScreen(
    deepLinkUri: Uri? = null,
    onDeepLinkConsumed: () -> Unit = {},
    themeMode: String = "system",
    onThemeMode: (String) -> Unit = {},
    fold: FoldingFeature? = null,
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val readiness by ClipForegroundService.readiness.collectAsState()
    val clipboardDiagnostics = ClipboardAccessDiagnostics.from(
        state.shizukuState, state.hasPairing, state.syncEnabled,
        readiness.helperRunning, readiness.clipboardReadable
    )
    val code by vm.pairingCode.collectAsState()
    val qrSecret by vm.pairingQrSecret.collectAsState()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.HOME) }
    var discoveredName by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(Prefs.DEFAULT_PORT.toString()) }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    // Confirmation is intentionally not restored after Activity recreation.
    var verified by remember { mutableStateOf(false) }
    var awaitingPairing by rememberSaveable { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Every navigation path (including the persistent expanded-pane list) ends the review session.
    val navigate: (SettingsDestination) -> Unit = { next ->
        if (destination == SettingsDestination.PAIRING && next != SettingsDestination.PAIRING) {
            vm.clearPairingCode()
            verified = false
        }
        destination = next
    }
    val holder = rememberSaveableStateHolder()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onNotificationPermissionResult(it)
    }
    LaunchedEffect(Unit) { vm.bootstrap(context.applicationContext) }
    val owner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshOnResume(context.applicationContext)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            if (!state.pairingInProgress && uri.scheme == "clipsync" && uri.host == "pair") {
                // A link is untrusted input. It opens review only; no network or trust mutation here.
                discoveredName = null
                host = uri.getQueryParameter("host").orEmpty().take(253)
                port = uri.getQueryParameter("port")?.take(5) ?: Prefs.DEFAULT_PORT.toString()
                fingerprint = uri.getQueryParameter("fp").orEmpty().take(43)
                vm.clearPairingCode()
                if (uri.getQueryParameter("v") == "2") {
                    if (listOf("v", "host", "port", "fp", "secret").all { uri.getQueryParameters(it).size == 1 })
                        vm.updatePairingQrSecret(uri.getQueryParameter("secret").orEmpty())
                } else if (uri.getQueryParameter("v") == null) {
                    vm.updatePairingCode(uri.getQueryParameter("code").orEmpty())
                }
                verified = false
                navigate(SettingsDestination.PAIRING)
            }
            onDeepLinkConsumed()
        }
    }
    LaunchedEffect(state.pairingInProgress) {
        if (awaitingPairing && !state.pairingInProgress) {
            awaitingPairing = false
            if (state.hasPairing && state.errors.isEmpty()) navigate(SettingsDestination.CONNECTION)
        }
    }
    BackHandler(destination != SettingsDestination.HOME) {
        navigate(SettingsDestination.HOME)
    }
    val status = when {
        !state.hasPairing -> "Setup needed"
        !state.syncEnabled -> "Paused"
        !readiness.helperAuthorized || !readiness.helperRunning || !readiness.clipboardReadable -> "Setup needed"
        !readiness.networkConnected -> "Reconnecting"
        else -> "Ready"
    }
    val beginPairing: () -> Unit = {
        discoveredName = null
        vm.clearPairingCode(); host = ""; port = Prefs.DEFAULT_PORT.toString(); fingerprint = ""; verified = false
        navigate(SettingsDestination.PAIRING)
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
        .onGloballyPositioned { val pos = it.positionInWindow(); offsetX = pos.x; offsetY = pos.y }) {
        AdaptiveSettingsLayout(destination != SettingsDestination.HOME, fold, offsetX, offsetY,
            compactStatus = { SettingsRow(state.pairedName ?: state.pairedHost ?: "ClipSync", status) },
            list = {
                holder.SaveableStateProvider("home") {
                    SettingsPage("ClipSync") {
                        StatusGroup(state.pairedName ?: state.pairedHost, status, state.hasPairing, state.syncEnabled) {
                            if (it) vm.startSync(context) else vm.stopSync(context)
                        }
                        if (status == "Setup needed") SettingsGroup {
                            SettingsRow("Finish setup", "Review connection and clipboard access", { navigate(SettingsDestination.SETUP) })
                        }
                        SettingsGroup {
                            SettingsRow("Connection", if (state.hasPairing) "Paired Mac and network" else "Pair your Mac", { navigate(SettingsDestination.CONNECTION) })
                            SettingsDivider()
                            SettingsRow("Clipboard", "Automatic text and image sharing", { navigate(SettingsDestination.CLIPBOARD) })
                            SettingsDivider()
                            SettingsRow("Privacy", "Sensitive items and notifications", { navigate(SettingsDestination.PRIVACY) })
                        }
                        SettingsGroup {
                            SettingsRow("Setup & diagnostics", "Permissions, helper and connection details", { navigate(SettingsDestination.SETUP) })
                            SettingsDivider()
                            ThemeRow(themeMode, onThemeMode)
                        }
                    }
                }
            },
            detail = {
                holder.SaveableStateProvider(destination.name) {
                    SettingsPage(if (destination == SettingsDestination.HOME) "Connection" else destination.title,
                        onBack = if (destination != SettingsDestination.HOME) ({
                            navigate(SettingsDestination.HOME)
                        }) else null) {
                        when (destination) {
                            SettingsDestination.HOME, SettingsDestination.CONNECTION -> {
                                SettingsGroup {
                                    SettingsRow("Paired Mac", (state.pairedName ?: state.pairedHost).takeIf { state.hasPairing } ?: "No Mac paired")
                                    SettingsDivider()
                                    SettingsRow("Connection", status)
                                    if (state.hasPairing) {
                                        SettingsDivider()
                                        SettingsRow("Route", "${state.pairedHost}:${state.pairedPort}")
                                        PillAction(if (state.syncEnabled) "Pause sync" else "Resume sync") {
                                            if (state.syncEnabled) vm.stopSync(context) else vm.startSync(context)
                                        }
                                    }
                                    PillAction(if (state.hasPairing) "Pair a different Mac" else "Pair Mac", onClick = beginPairing)
                                    if (state.hasPairing) PillAction("Remove paired Mac") { navigate(SettingsDestination.REMOVE) }
                                }
                                if (!state.hasPairing) SettingsGroup("Nearby Macs") {
                                    if (state.discovered.isEmpty()) SettingsRow("Searching the local network", "Keep ClipSync open on your Mac, or enter its address in Pair Mac.")
                                    state.discovered.forEach { peer ->
                                        SettingsRow(peer.name, "${peer.host}:${peer.port}", {
                                            discoveredName = peer.name
                                            host = peer.host; port = peer.port.toString(); fingerprint = ""; verified = false
                                            vm.clearPairingCode(); navigate(SettingsDestination.PAIRING)
                                        })
                                    }
                                }
                            }
                            SettingsDestination.CLIPBOARD -> {
                                SettingsGroup {
                                    SettingsSwitch("Send automatically", "Send eligible copied text and images while sync is active", state.autoSendEnabled,
                                        onCheckedChange = { vm.setAutoSendEnabled(context, it) })
                                    SettingsDivider()
                                    SettingsRow("Images", "Use Android Share → Mac when an image provider cannot grant clipboard access. Image paste depends on the receiving app.")
                                    SettingsDivider()
                                    SettingsRow("Explicit send", "Select Mac in Android's share sheet to send supported text or an image.")
                                }
                            }
                            SettingsDestination.PRIVACY -> {
                                SettingsGroup {
                                    SettingsRow("Sensitive content", "Items marked sensitive by the source app are excluded from automatic sync. Apps must mark their sensitive items correctly.")
                                    SettingsDivider()
                                    SettingsRow("Private notifications", "Clipboard text and image previews are hidden.")
                                    SettingsDivider()
                                    SettingsRow("Temporary images", "Received images stay in the app cache for paste. No permanent Gallery copies or screenshot observation.")
                                    SettingsDivider()
                                    SettingsRow("Notification settings", "Manage ClipSync notifications in Android", {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    })
                                }
                            }
                            SettingsDestination.SETUP -> {
                                SettingsGroup("Clipboard access") {
                                    SettingsRow("Shizuku", when (state.shizukuState) {
                                        "ready" -> "Authorized. Helper status is shown below."
                                        "not_installed" -> "Install Shizuku to allow background clipboard access."
                                        "no_permission" -> "Allow ClipSync in Shizuku."
                                        else -> "Start Shizuku. It needs restarting after a phone reboot."
                                    })
                                    PillAction(if (state.shizukuState == "no_permission") "Allow clipboard access" else "Open Shizuku") {
                                        if (state.shizukuState == "no_permission") vm.requestShizukuPermission()
                                        else {
                                            val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                                        }
                                    }
                                    SettingsDivider()
                                    SettingsRow("Notifications", if (state.notificationPermissionGranted) "Allowed" else "Allow connection and incoming item notifications")
                                    if (!state.notificationPermissionGranted) PillAction("Allow notifications") {
                                        if (android.os.Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                SettingsGroup("Diagnostics") {
                                    SettingsRow("Network connection", if (readiness.networkConnected) "Connected" else "Unavailable")
                                    SettingsDivider()
                                    SettingsRow("Helper authorization", clipboardDiagnostics.authorization)
                                    SettingsDivider()
                                    SettingsRow("Helper process", clipboardDiagnostics.process)
                                    SettingsDivider()
                                    SettingsRow("Clipboard access", clipboardDiagnostics.clipboard)
                                    readiness.lastIssue?.let { SettingsRow("Recovery", it) }
                                    state.errors.forEach { error ->
                                        SettingsDivider()
                                        SettingsRow(error.summary, error.suggestion)
                                        PillAction("Dismiss") { vm.dismissError(error.id) }
                                    }
                                    PillAction("Refresh status") { vm.refreshOnResume(context) }
                                }
                                SettingsGroup("Away from home") {
                                    SettingsRow("Private network", "For remote sync, connect both devices to your private VPN and pair using the Mac's reachable address. The Mac must be awake.")
                                    PillAction("Open Tailscale") {
                                        val launch = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                                        if (launch != null) context.startActivity(launch) else vm.openTailscalePlayStore(context)
                                    }
                                }
                            }
                            SettingsDestination.REMOVE -> SettingsGroup {
                                SettingsRow("Remove paired Mac?", "This stops sync and removes this phone's stored trust and credentials. Pair again to reconnect.")
                                PillAction("Remove Mac") { vm.unpair(context); navigate(SettingsDestination.CONNECTION) }
                                PillAction("Cancel") { navigate(SettingsDestination.CONNECTION) }
                            }
                            SettingsDestination.PAIRING -> {
                                PairingReview(host, port, fingerprint, code, verified, state.hasPairing, state.pairingInProgress,
                                    onHost = { host = it.take(253); discoveredName = null; verified = false },
                                    onPort = { port = it.take(5); verified = false },
                                    onFingerprint = { fingerprint = it.take(43); verified = false },
                                    onCode = { vm.updatePairingCode(it) }, onVerified = { verified = it },
                                    onConfirm = {
                                        awaitingPairing = true
                                        val target = discoveredName?.let { name ->
                                            PairingTarget.Auto(com.clipsync.discovery.Discovered(host.trim(), port.toInt(), null, name))
                                        } ?: PairingTarget.Manual(host.trim(), port.toInt())
                                        vm.pair(context, target, code, fingerprint.trim(), state.hasPairing, qrSecret.takeIf { it.isNotEmpty() })
                                        vm.clearPairingCode(); verified = false
                                    }, hasQrSecret = qrSecret.isNotEmpty())
                                if (state.errors.isNotEmpty()) SettingsGroup {
                                    state.errors.forEach { SettingsRow(it.summary, it.suggestion) }
                                }
                            }
                        }
                    }
                }
            })
    }

}

@Composable
internal fun SettingsPage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            Text(title, Modifier.padding(start = 8.dp, top = if (compact) 0.dp else 20.dp, bottom = 4.dp).semantics { heading() },
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary)
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun StatusGroup(peer: String?, status: String, paired: Boolean, enabled: Boolean, onSync: (Boolean) -> Unit) {
    SettingsGroup {
        SettingsRow(peer.takeIf { paired } ?: "Your Mac", status)
        SettingsDivider()
        SettingsSwitch("Sync", if (paired) "Keep your devices connected" else "Pair a Mac to enable sync", enabled && paired, paired,
            onCheckedChange = onSync)
    }
}

@Composable
internal fun PairingReview(host: String, port: String, fingerprint: String, code: String, verified: Boolean,
    replacing: Boolean, busy: Boolean, onHost: (String) -> Unit, onPort: (String) -> Unit,
    onFingerprint: (String) -> Unit, onCode: (String) -> Unit, onVerified: (Boolean) -> Unit, onConfirm: () -> Unit, hasQrSecret: Boolean = false) {
    SettingsGroup {
        SettingsRow(if (replacing) "Replace your paired Mac" else "Connect to your Mac",
            "Open pairing on your Mac. Compare the complete fingerprint on its screen before confirming. A link or nearby device is not proof of identity.")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(host, onHost, label = { Text("Mac address") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(port, onPort, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(fingerprint, onFingerprint, label = { Text("SPKI fingerprint from Mac") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (!hasQrSecret) OutlinedTextField(code, onCode, label = { Text("Six-digit pairing code") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            Text(if (hasQrSecret) "Single-use pairing link received. It is cleared after two minutes or when you leave this review." else "Codes are cleared after two minutes or when you leave this review.", style = MaterialTheme.typography.bodyMedium)
        }
        SettingsSwitch("I compared the fingerprint", "It matches the trusted Mac's screen", verified, !busy, onCheckedChange = onVerified)
        PillAction(if (busy) "Pairing…" else if (replacing) "Replace paired Mac" else "Confirm pairing",
            enabled = !busy && verified && host.isNotBlank() && port.toIntOrNull() in 1..65535 &&
                fingerprint.matches(Regex("[A-Za-z0-9_-]{43}")) && (hasQrSecret || code.matches(Regex("[0-9]{6}"))), onClick = onConfirm)
    }
}

@Composable
private fun ThemeRow(mode: String, onMode: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow("Appearance", when (mode) { "light" -> "Light"; "dark" -> "Dark"; else -> "Follow system" }, { expanded = true })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                DropdownMenuItem(text = { Text(if (mode == value) "✓ $label" else label) },
                    onClick = { onMode(value); expanded = false })
            }
        }
    }
}
