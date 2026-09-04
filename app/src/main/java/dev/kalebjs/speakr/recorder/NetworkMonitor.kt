package dev.kalebjs.speakr.recorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Metered-network detection: true when the active network is mobile data. */
object NetworkMonitor {

    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /**
     * Process-lifetime callback: fires whenever the default network becomes
     * unmetered (Wi-Fi). Used to wake the upload queue without polling.
     */
    fun watchUnmetered(context: Context, onUnmetered: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        onUnmetered()
                    }
                }
            })
        } catch (_: Exception) {
            // Callbacks unavailable (rare) — app-open resume kicks cover it.
        }
    }
}