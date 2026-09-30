package com.jrs8205.appletvremote.lgtv

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LgTvClientTest {

    private val lg = FakeLgTv()

    @After
    fun stop() {
        lg.assertClientClosed()
        lg.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun client(pinned: String? = null, openTimeoutMs: Long = 4_000, handshakeTimeoutMs: Long = 5_000) =
        LgTvClient(lg.hostName, pinnedCertificate = pinned, port = lg.port, openTimeoutMs = openTimeoutMs, handshakeTimeoutMs = handshakeTimeoutMs)

    @Test
    fun registersAndReportsTheCertificate() = test {
        lg.serve(prompt = true)
        var prompted = false
        client().use { client ->
            assertNull(client.certificate)
            assertEquals("key-123", client.connect(null, onPrompt = { prompted = true }))
            assertEquals(lg.certificatePin, client.certificate)
            client.switchInput("HDMI_2")
        }
        assertTrue(prompted)
        val register = lg.received.first { it.getString("type") == "register" }
        assertEquals("PROMPT", register.getJSONObject("payload").getString("pairingType"))
        assertTrue(register.getJSONObject("payload").has("manifest"))
        val switch = lg.received.first { it.getString("type") == "request" }
        assertEquals("ssap://tv/switchInput", switch.getString("uri"))
        assertEquals("HDMI_2", switch.getJSONObject("payload").getString("inputId"))
    }

    @Test
    fun reportsOnlyTheConnectedAdaptersMacAddress() = test {
        lg.serve(connectedAdapter = "wifi")
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
    }

    @Test
    fun reportsEveryMacAddressWhenTheTvCannotSayWhichIsInUse() = test {
        lg.serve(connectedAdapter = null)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
    }

    @Test
    fun doesNotAskForStatusWhenGetinfoAlreadySaysWhichAdapterIsConnected() = test {
        lg.serve(connectedAdapter = "wired", statesInInfo = true)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("11:22:33:44:55:66"), client.macAddresses())
        }
        assertEquals(emptyList<String>(), lg.received.map { it.optString("uri") }.filter { it.endsWith("getStatus") })
    }

    @Test
    fun doesNotAskForStatusWhenOnlyOneAdapterHasAMacAddress() = test {
        lg.serve(connectedAdapter = null, wired = false)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
        assertEquals(emptyList<String>(), lg.received.map { it.optString("uri") }.filter { it.endsWith("getStatus") })
    }

    @Test
    fun aCancelledLookupStopsInsteadOfReturningAnEmptyList() = test {
        lg.serve(delayMs = 600)
        client().use { client ->
            client.connect(null)
            var finished = false
            coroutineScope {
                val lookup = launch {
                    client.macAddresses()
                    finished = true
                }
                delay(150)
                lookup.cancelAndJoin()
            }
            assertFalse("macAddresses returned after being cancelled", finished)
        }
    }

    @Test
    fun listsTheExternalInputsWithTheirLabels() = test {
        lg.serve(inputs = listOf(LgInput("HDMI_1", "HDMI 1", connected = false), LgInput("HDMI_2", "Apple TV", connected = true)))
        client().use { client ->
            client.connect(null)
            assertEquals(listOf(LgInput("HDMI_1", "HDMI 1", false), LgInput("HDMI_2", "Apple TV", true)), client.externalInputs())
        }
        assertEquals("ssap://tv/getExternalInputList", lg.received.last().getString("uri"))
    }

    @Test
    fun aPromptThatArrivesAfterTheRegistrationTimedOutIsIgnored() = test {
        lg.serve(delayMs = 600, prompt = true)
        var prompted = false
        val error = runCatching { client().use { it.connect(null, onPrompt = { prompted = true }, timeoutMs = 300) } }.exceptionOrNull()
        assertTrue("got $error", error is LgTvException && error.message!!.contains("no reply"))
        delay(1_000)
        assertFalse("a prompt for a registration that already failed was reported", prompted)
    }

    @Test
    fun sendsTheStoredKeyWhenReconnecting() = test {
        lg.serve()
        client(pinned = lg.certificatePin).use { it.connect("key-123") }
        assertEquals("key-123", lg.received.first().getJSONObject("payload").getString("client-key"))
    }

    @Test
    fun refusesATvWhoseKeyDoesNotMatchThePin() = test {
        // No response is enqueued: the handshake must fail before any request reaches the server.
        val error = runCatching { client(pinned = "00".repeat(32)).use { it.connect("key-123") } }.exceptionOrNull()
        assertTrue("got $error", error is LgTvException && error.permanent && error.message!!.contains("certificate"))
        assertEquals(0, lg.received.size)
    }

    @Test
    fun aRegistrationTheTvRefusesIsPermanent() = test {
        lg.serve(refuseRegistration = true)
        val error = runCatching { client().use { it.connect("key-123") } }.exceptionOrNull()
        assertTrue("got $error", error is LgTvException && error.permanent && error.message!!.contains("403 cancelled"))
    }

    @Test
    fun requestFailuresCarryTheTvsMessage() = test {
        lg.serve()
        client().use { client ->
            client.connect(null)
            val error = runCatching { client.switchInput("HDMI_9") }.exceptionOrNull()
            assertEquals("no such input", error?.message)
            assertFalse("a failed request is worth retrying", (error as LgTvException).permanent)
        }
    }

    @Test
    fun slowRepliesAreNotCutOffByTheHandshakeTimeout() = test {
        lg.serve(delayMs = 700)
        client(handshakeTimeoutMs = 250).use { client ->
            assertEquals("key-123", client.connect(null))
        }
    }

    @Test
    fun aStalledHandshakeIsCancelledNotLeftHanging() = test {
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var peerClosedAt = 0L
        val acceptor = thread(isDaemon = true) {
            runCatching {
                silent.accept().use { socket ->
                    while (socket.getInputStream().read() >= 0) { /* swallow the client hello */ }
                    peerClosedAt = System.nanoTime()
                }
            }
        }
        try {
            val client = LgTvClient("127.0.0.1", port = silent.localPort, openTimeoutMs = 300)
            val started = System.nanoTime()
            val error = runCatching { client.connect(null) }.exceptionOrNull()
            assertTrue("got $error", error is LgTvException && error.message!!.contains("timeout"))
            acceptor.join(3_000)
            assertTrue("the socket was not cancelled", peerClosedAt > started)
            assertTrue((peerClosedAt - started) / 1_000_000 < 2_000)
        } finally {
            silent.close()
        }
    }
}
