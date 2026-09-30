package com.jrs8205.appletvremote.discovery

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import javax.net.SocketFactory

class AndroidNetworkTargets(private val connectivity: ConnectivityManager) : NetworkTargets {

    override fun bindToLan(socket: DatagramSocket) {
        val lan = lanNetwork() ?: return
        runCatching { lan.bindSocket(socket) }
    }

    override fun lanSocketFactory(): SocketFactory? = lanNetwork()?.socketFactory

    override fun lanAvailable(): Boolean = lanNetwork() != null

    private fun lanNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }

    override fun broadcastAddresses(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val lan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan) continue
            val properties = connectivity.getLinkProperties(network) ?: continue
            for (link in properties.linkAddresses) {
                val address = link.address as? Inet4Address ?: continue
                val prefix = link.prefixLength
                if (prefix <= 0 || prefix >= 31) continue
                val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val mask = (-1 shl (32 - prefix))
                val broadcast = ip or mask.inv()
                targets += InetAddress.getByAddress(byteArrayOf((broadcast ushr 24).toByte(), (broadcast ushr 16).toByte(), (broadcast ushr 8).toByte(), broadcast.toByte()))
            }
        }
        return targets.toList()
    }
}
