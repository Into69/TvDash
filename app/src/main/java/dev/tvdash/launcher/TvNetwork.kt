package dev.tvdash.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** This TV's address on the home network and how it's connected. */
data class TvAddress(val ip: String, val ethernet: Boolean)

object TvNetwork {
    /**
     * The IPv4 address of the network Android is using now, or null when offline. Falls back to any
     * private IPv4 address if the system doesn't say, as some TV firmware doesn't for Ethernet.
     */
    fun current(context: Context): TvAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val ethernet = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: anyPrivateIpv4()
            ?: return null
        return TvAddress(ip, ethernet)
    }

    private fun anyPrivateIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
