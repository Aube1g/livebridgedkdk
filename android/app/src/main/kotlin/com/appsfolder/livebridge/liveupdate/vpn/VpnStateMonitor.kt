package com.appsfolder.livebridge.liveupdate.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.appsfolder.livebridge.liveupdate.LiveUpdateNotifier

/**
 * System-level source of truth for "a VPN is active".
 *
 * The conversion engine only sees VPN traffic through the VPN app's own
 * notification text, and many clients never publish a speed there. This
 * monitor watches the default network for TRANSPORT_VPN via
 * ConnectivityManager.NetworkCallback, so the capsule can always show a
 * minimal VPN pill (app icon + "VPN") even when the VPN client is silent.
 */
object VpnStateMonitor {
    private const val TAG = "VpnStateMonitor"

    private var started = false
    private var connectivity: ConnectivityManager? = null
    private var activeVpnPackage: String? = null
    private var lastPushedPackage: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (started) {
            return
        }
        started = true
        try {
            val connectivity =
                context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            this.connectivity = connectivity
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    handleCapabilities(capabilities)
                }

                override fun onLost(network: Network) {
                    handleCapabilities(NetworkCapabilities())
                }
            }
            callback = networkCallback
            connectivity.registerDefaultNetworkCallback(networkCallback)
            Log.d(TAG, "started")
        } catch (error: Throwable) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", error)
            callback = null
        }
    }

    fun stop() {
        started = false
        val networkCallback = callback
        callback = null
        if (networkCallback != null) {
            try {
                connectivity?.unregisterNetworkCallback(networkCallback)
            } catch (error: Throwable) {
                // Already detached or service gone.
            }
        }
        connectivity = null
        activeVpnPackage = null
        lastPushedPackage = null
        try {
            LiveUpdateNotifier.capsuleOverlay?.clearVpnFallback()
        } catch (error: Throwable) {
            // ignore
        }
    }

    private fun handleCapabilities(capabilities: NetworkCapabilities) {
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        Log.d(TAG, "vpn state: $isVpn")
        if (isVpn) {
            if (activeVpnPackage == null) {
                activeVpnPackage = "active"
                try {
                    LiveUpdateNotifier.capsuleOverlay?.showVpnFallback(null)
                } catch (error: Throwable) {
                    Log.w(TAG, "showVpnFallback failed", error)
                }
            }
        } else {
            if (activeVpnPackage != null) {
                activeVpnPackage = null
                lastPushedPackage = null
                try {
                    LiveUpdateNotifier.capsuleOverlay?.clearVpnFallback()
                } catch (error: Throwable) {
                    Log.w(TAG, "clearVpnFallback failed", error)
                }
            }
        }
    }
}
