package com.sectl.litertlm.server

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Returns the wlan0 / primary LAN IPv4 address, or null if not reachable.
 *
 * We deliberately skip loopback, link-local (169.254.*), and non-IPv4
 * addresses. Preference order: an interface named wlan0 first, then any
 * up interface with a private IPv4.
 */
object NetworkUtil {
    private const val TAG = "NetworkUtil"

    fun primaryLanIp(): String? {
        return try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

            val wlan = all.firstOrNull { it.name == "wlan0" && it.isUp && !it.isLoopback }
            val candidates = (wlan?.let { listOf(it) } ?: emptyList()) +
                all.filter { it.isUp && !it.isLoopback && it != wlan }

            for (iface in candidates) {
                val addr = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                if (addr != null) return addr.hostAddress
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "primaryLanIp failed", t)
            null
        }
    }
}
