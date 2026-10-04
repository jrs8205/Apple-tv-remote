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
import com.jrs8205.appletvremote.lgtv.FakeLgTv
import com.jrs8205.appletvremote.lgtv.LgInput
import com.jrs8205.appletvremote.lgtv.LgTvClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
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
        lgTvClients = { host, pin -> LgTvClient(host, log, pinnedCertificate = pin, port = lgPort) },
    )
    /** Where the LG TV would answer; nothing listens there unless a test says otherwise. */
    private var lgPort = closedPort()

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

    private suspend fun awaitFailure() = controller.state.first { it.connection is ConnectionState.Failed }

    /** A port nobody listens on, so connecting fails at once. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

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

    @Test
    fun connectRequestsQueuedTogetherOpenOneSession() = test {
        pairWith(port = closedPort())
        controller.connect()
        controller.connect()
        awaitFailure()
        delay(1_500)

        assertEquals(1, lines.count { it.contains("connecting to") })
    }

    @Test
    fun aFailedConnectDoesNotSearchForTheTv() = test {
        pairWith(port = closedPort())
        controller.connect()
        awaitFailure()
        delay(1_000)

        assertEquals(0, lines.count { it.contains("resolved by mDNS") })
    }

    private suspend fun configureLgTv() {
        lgTvRepository.setEnabled(true)
        lgTvRepository.setHost("127.0.0.1")
    }

    @Test
    fun theAppleTvIsWokenWhileTheLgTvIsStillBeingReached() = test {
        pairWith()
        configureLgTv()

        controller.wakeAndConnect()

        tv.awaitMessage("_hidC")
        assertFalse("the LG phase had already given up", lines.any { it.contains("LG TV did not respond") })
        controller.state.first { !it.wakingTv }
        assertTrue("the wake-up waited for the LG phase", lines.any { it.contains("LG TV did not respond") })
    }

    @Test
    fun attemptsMadeWhileTheLgTvIsBeingReachedAreNotCounted() = test {
        pairWith(port = closedPort())
        configureLgTv()

        controller.wakeAndConnect()

        controller.state.first { !it.wakingTv }
        val failures = lines.filter { it.contains("Apple TV attempt") || it.contains("Apple TV not reachable") }
        assertTrue("only ${failures.size} attempts: $failures", failures.size > timings.wakeConnectAttempts)
        assertTrue(lines.any { it.contains("wake-up failed") })
    }

    @Test
    fun pairingWithTheLgTvLearnsWhichInputTheAppleTvIsOn() = test {
        FakeLgTv().use { lg ->
            lgPort = lg.port
            lg.serve(prompt = true, inputs = listOf(LgInput("HDMI_1", "HDMI 1", connected = false), LgInput("HDMI_2", "Apple TV", connected = true)))

            val result = controller.pairLgTv("127.0.0.1") {}

            assertTrue("pairing failed: $result", result.isSuccess)
            val settings = lgTvRepository.settings.first()
            assertEquals("HDMI_2", settings.inputId)
            assertEquals(listOf("HDMI 1", "Apple TV"), settings.inputs.map { it.label })
            assertEquals("key-123", settings.clientKey)
        }
    }

    @Test
    fun aFailedWakeUpIsNotRepeatedThroughAddressRecovery() = test {
        val asleep = closedPort()
        pairWith(port = asleep)
        discovered.value = listOf(DiscoveredDevice("Living Room", "127.0.0.1", asleep, "AppleTV14,1", null))

        controller.wakeAndConnect()

        controller.state.first { !it.wakingTv }
        delay(1_500)
        assertEquals(1, lines.count { it.contains("wake-up started") })
    }

    @Test
    fun theWakeUpSaysItIsWaitingForTheLgTvUntilTheTvAnswers() = test {
        pairWith(port = closedPort())
        configureLgTv()

        controller.wakeAndConnect()

        assertEquals(WakeStage.LG_TV, controller.state.first { it.wakeProgress != null }.wakeProgress?.stage)
        assertNull(controller.state.first { !it.wakingTv }.wakeProgress)
    }

    @Test
    fun theWakeUpWaitsForTheAppleTvOnceTheLgTvHasSwitchedInput() = test {
        FakeLgTv().use { lg ->
            lgPort = lg.port
            lg.serve()
            pairWith(port = closedPort())
            configureLgTv()

            controller.wakeAndConnect()

            controller.state.first { it.wakeProgress?.stage == WakeStage.APPLE_TV }
            assertTrue("the stage changed before the LG TV switched input", lines.any { it.contains("LG TV switched") })
        }
    }

    @Test
    fun withoutAnLgTvTheWakeUpWaitsForTheAppleTvFromTheStart() = test {
        pairWith(port = closedPort())

        controller.wakeAndConnect()

        assertEquals(WakeStage.APPLE_TV, controller.state.first { it.wakeProgress != null }.wakeProgress?.stage)
    }
}
