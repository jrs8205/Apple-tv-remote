package com.jrs8205.appletvremote.discovery

import java.net.DatagramSocket
import java.net.InetAddress
import javax.net.SocketFactory

/** The LAN the TVs are on, as seen from the phone; the app asks ConnectivityManager, tests answer for loopback. */
interface NetworkTargets {
    /** Binds a socket to the Wi-Fi or Ethernet network; a phone with mobile data would otherwise send broadcasts nowhere. */
    fun bindToLan(socket: DatagramSocket)

    /** TCP sockets that stay on the LAN for the same reason; null when the phone has no Wi-Fi or Ethernet link. */
    fun lanSocketFactory(): SocketFactory?

    fun lanAvailable(): Boolean

    /** Where to send broadcasts: the limited broadcast plus the directed broadcast of every LAN interface. */
    fun broadcastAddresses(): List<InetAddress>
}
