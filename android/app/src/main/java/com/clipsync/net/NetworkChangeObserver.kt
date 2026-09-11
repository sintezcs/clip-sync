package com.clipsync.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.clipsync.sync.DefaultNetworkState
import com.clipsync.util.L

/** Watches only the default route. Registration establishes a baseline; loss and route changes reconnect. */
class NetworkChangeObserver(
    private val context: Context,
    private val onReconnectNeeded: () -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var state = DefaultNetworkState<Network>()
    private var registered = false

    /** Internet validation is not required: local-only LAN peers remain usable. */
    fun register() {
        if (registered) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (state.available(network)) onReconnectNeeded()
            }

            override fun onLost(network: Network) {
                if (state.lost(network)) onReconnectNeeded()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                L.verbose(M, "Capabilities changed: $network validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        callback = cb
        registered = true
        L.event(M, "NetworkChangeObserver registered")
    }

    /**
     * Stop listening. Safe to call even if never registered.
     */
    fun unregister() {
        if (!registered) return
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Throwable) {
                // Already unregistered or context destroyed
            }
        }
        callback = null
        state = DefaultNetworkState()
        registered = false
        L.event(M, "NetworkChangeObserver unregistered")
    }

    companion object {
        private const val M = "Net"
    }
}
