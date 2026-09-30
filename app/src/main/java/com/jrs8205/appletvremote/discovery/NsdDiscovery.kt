package com.jrs8205.appletvremote.discovery

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

data class DiscoveredDevice(
    val serviceName: String,
    val host: String,
    val port: Int,
    val model: String,
    val network: Network?,
)

/** Browses `_companion-link._tcp` and keeps a list of Apple TVs with a usable IPv4 address. */
class NsdDiscovery(context: Context, private val log: ProtocolLog? = null) : DeviceDiscovery {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override fun devices(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val lock = wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        fun publish() {
            val devices = synchronized(found) { found.values.toList() }
            trySend(devices)
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
                log?.log { "mDNS browse failed to start: error $errorCode" }
                close(IllegalStateException("discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                if (callbacks.containsKey(name)) return
                log?.log { "mDNS found $name, resolving" }
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val device = info.toDevice()
                        val changed = synchronized(found) {
                            val before = found[name]
                            if (device == null) found.remove(name) else found[name] = device
                            before != device
                        }
                        if (changed) log?.log { if (device == null) "mDNS $name has no usable IPv4 address yet" else "mDNS resolved $name: ${device.host}:${device.port} (${device.model})" }
                        publish()
                    }

                    override fun onServiceLost() {
                        synchronized(found) { found.remove(name) }
                        log?.log { "mDNS lost $name" }
                        publish()
                    }

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.w(TAG, "resolve registration failed for $name: $errorCode")
                        log?.log { "mDNS resolve of $name failed to start: error $errorCode" }
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                callbacks[name] = callback
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                synchronized(found) { found.remove(name) }
                callbacks.remove(name)?.let { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
                publish()
            }
        }

        log?.log { "mDNS browse started${if (lock == null) " without a multicast lock" else ""}" }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        publish()

        awaitClose {
            log?.log { "mDNS browse stopped" }
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            callbacks.values.forEach { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
            lock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun NsdServiceInfo.toDevice(): DiscoveredDevice? {
        val model = attributes["rpMd"]?.toString(Charsets.UTF_8) ?: ""
        if (!model.startsWith("AppleTV", ignoreCase = true)) return null
        if (port <= 0) return null
        val v4 = hostAddresses.filterIsInstance<Inet4Address>()
        val address = v4.firstOrNull { !it.isLinkLocalAddress } ?: v4.firstOrNull() ?: return null
        return DiscoveredDevice(serviceName, address.hostAddress ?: return null, port, model, network)
    }

    private companion object {
        const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_companion-link._tcp."
        const val LOCK_TAG = "appletvremote-nsd"
    }
}
