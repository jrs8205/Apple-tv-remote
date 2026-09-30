package com.jrs8205.appletvremote.remote

import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.FakeDataStore
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.LgTvRepository
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.data.PlainCipher
import com.jrs8205.appletvremote.discovery.DeviceDiscovery
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.discovery.NetworkTargets
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.FakeAppleTv
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.PlainSocketConnector
import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory
import com.jrs8205.appletvremote.protocol.pairing.PairSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

class RemoteControllerTest {

    private val identity = ControllerIdentity("3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d", Ed25519KeyPair.generate(SecureRandomSource), "Remote")
    private val accessory = FakeAccessory()
    private val credentials: Credentials = PairSetup(identity, SecureRandomSource).let { setup ->
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        setup.finish(accessory.handleSetupM5(setup.m5(m4)))
    }
    private val tv = FakeAppleTv(accessory)
    private val dataStore = FakeDataStore()
    private val deviceRepository = DeviceRepository(dataStore, PlainCipher())
    private val lgTvRepository = LgTvRepository(dataStore)
    private val discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    private val lines = CopyOnWriteArrayList<String>()
    private val log = ConnectionLog(sink = lines::add, logcat = {})
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timings = RemoteController.Timings(
        backgroundDisconnectMs = 200,
        wakeRetryDelayMs = 100,
        wakeConnectAttempts = 3,
        addressRefreshMs = 500,
        lgWakeTimeoutMs = 1_500,
        lgRetryDelayMs = 100,
        lgCecSettleMs = 50,
    )
    private val controller = RemoteController(
        scope = scope,
        deviceRepository = deviceRepository,
        identityRepository = IdentityRepository(dataStore),
        connector = PlainSocketConnector,
        networkTargets = Loopback,
        discovery = DeviceDiscovery { discovered },
        lgTvRepository = lgTvRepository,
        clientName = "Remote",
        clientModel = "Test",
        log = log,
        timings = timings,
    )

    private object Loopback : NetworkTargets {
        override fun bindToLan(socket: DatagramSocket) = Unit
        override fun lanSocketFactory(): SocketFactory? = null
        override fun lanAvailable() = true
        override fun broadcastAddresses(): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    @After
    fun tearDown() {
        scope.cancel()
        tv.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun pairWith(port: Int = tv.port) {
        deviceRepository.save(PairedDevice("Living Room", "127.0.0.1", port, credentials))
        controller.state.first { it.device != null }
    }

    private suspend fun awaitConnection(expected: ConnectionState) = controller.state.first { it.connection == expected }

    @Test
    fun connectOpensASessionWithThePairedTv() = test {
        pairWith()
        controller.connect()
        awaitConnection(ConnectionState.Ready)
        assertEquals(1, tv.connectionCount)
    }

    @Test
    fun aFailedClientLookupDoesNotStopTheQueue() = test {
        pairWith()
        dataStore.readFailure = IOException("settings unreadable")
        controller.press(HidButton.MENU)
        controller.state.first { it.lastError != null }
        dataStore.readFailure = null

        controller.press(HidButton.MENU)

        tv.awaitMessage("_hidC")
    }

    @Test
    fun aSecondWakeUpWhileOneIsRunningIsIgnored() = test {
        pairWith()
        controller.wakeAndConnect()
        controller.wakeAndConnect()
        controller.press(HidButton.MENU)

        tv.awaitMessage("_hidC", timeoutMs = 8_000, skip = 2)

        val wakes = tv.messages.count { it.name == "_hidC" && it.content["_hidC"] == HidButton.WAKE.code.toLong() && it.content["_hBtS"] == 1L }
        assertEquals(1, wakes)
        assertEquals(1, lines.count { it.contains("wake-up started") })
    }

    @Test
    fun aPressLostToADroppedConnectionIsRetriedAtTheSameAddress() = test {
        pairWith()
        discovered.value = listOf(DiscoveredDevice("Living Room", "127.0.0.1", tv.port, "AppleTV14,1", null))
        controller.connect()
        awaitConnection(ConnectionState.Ready)
        var dropped = false
        tv.responder = { name, _ ->
            if (name == "_hidC" && !dropped) {
                dropped = true
                tv.closeConnection()
                null
            } else {
                tv.defaultReply(name)
            }
        }

        controller.press(HidButton.MENU)

        tv.awaitMessage("_hidC", skip = 1)
        assertEquals(2, tv.connectionCount)
    }
}
