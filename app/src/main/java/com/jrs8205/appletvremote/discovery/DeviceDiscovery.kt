package com.jrs8205.appletvremote.discovery

import kotlinx.coroutines.flow.Flow

/** Where Apple TVs on the network are found; the app browses mDNS, tests hand in a list. */
fun interface DeviceDiscovery {
    fun devices(): Flow<List<DiscoveredDevice>>
}
