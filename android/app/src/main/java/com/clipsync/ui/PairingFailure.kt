package com.clipsync.ui

import com.clipsync.model.AppError
import com.clipsync.model.ErrorAction
import com.clipsync.model.ErrorSeverity
import com.clipsync.net.PairingApi
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** User guidance comes from typed failures, never untrusted response bodies. */
internal fun pairingFailure(error: Throwable): AppError {
    val status = (error as? PairingApi.PairingException)?.httpStatus
    val (summary, suggestion) = when {
        error is SSLPeerUnverifiedException -> "Certificate mismatch" to
            "Stop and compare the complete fingerprint directly on your trusted Mac before pairing again."
        error is SSLException -> "Secure connection failed" to
            "Check ClipSync on the Mac and compare its complete fingerprint before pairing again."
        status == 401 -> "Pairing session unavailable" to
            "Open pairing on your Mac and generate a new code or scan a fresh link, then compare the fingerprint and confirm again."
        status == 429 -> "Too many pairing attempts" to
            "Wait a minute, then open pairing on your Mac and use a fresh code or link."
        error is InterruptedIOException || error is ConnectException || error is NoRouteToHostException || error is UnknownHostException ->
            "Mac is unreachable" to "Keep ClipSync open on the Mac and check its address. On the same Wi-Fi, allow local network access in your VPN settings. For remote pairing, both devices need a reachable private VPN route."
        status != null && status >= 500 -> "Mac could not finish pairing" to
            "Check ClipSync and Keychain access on the Mac, then start a fresh pairing session."
        else -> "Pairing failed" to "Check the Mac address and fingerprint, then open a fresh pairing code or link on the Mac."
    }
    return AppError(severity = ErrorSeverity.ERROR, summary = summary, suggestion = suggestion,
        action = if (error is SSLException) ErrorAction.Repair else ErrorAction.Retry)
}
