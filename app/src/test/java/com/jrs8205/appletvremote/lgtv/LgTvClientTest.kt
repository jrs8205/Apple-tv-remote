package com.jrs8205.appletvremote.lgtv

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LgTvClientTest {

    private val held = HeldCertificate.Builder().commonName("LG Smart TV").build()
    private val expectedPin = MessageDigest.getInstance("SHA-256").digest(held.keyPair.public.encoded).joinToString("") { "%02x".format(it) }
    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<JSONObject>()
    private var serverSideClosed: CountDownLatch? = null

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun stop() {
        // A client that closes properly completes the close handshake; the server must see it before shutting down.
        serverSideClosed?.let { assertTrue("the client never closed the socket", it.await(5, TimeUnit.SECONDS)) }
        server.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun client(pinned: String? = null, openTimeoutMs: Long = 4_000, handshakeTimeoutMs: Long = 5_000) =
        LgTvClient(server.hostName, pinnedCertificate = pinned, port = server.port, openTimeoutMs = openTimeoutMs, handshakeTimeoutMs = handshakeTimeoutMs)

    /**
     * A TV that pairs on request and answers switchInput; [delayMs] holds every reply back.
     * It reports a wired and a Wi-Fi adapter (only Wi-Fi without [wired]); [connectedAdapter] is the one
     * getStatus calls connected, and with [statesInInfo] getinfo already carries those states itself.
     */
    private fun serveTv(
        delayMs: Long = 0,
        prompt: Boolean = false,
        connectedAdapter: String? = "wifi",
        wired: Boolean = true,
        statesInInfo: Boolean = false,
        refuseRegistration: Boolean = false,
    ) {
        val closed = CountDownLatch(1)
        serverSideClosed = closed
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = closed.countDown()

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    received += message
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val id = message.getString("id")
                    when (message.getString("type")) {
                        "register" -> {
                            if (refuseRegistration) {
                                webSocket.send(JSONObject().put("type", "error").put("id", id).put("error", "403 cancelled").toString())
                                return
                            }
                            if (prompt) webSocket.send(JSONObject().put("type", "response").put("id", id).put("payload", JSONObject().put("pairingType", "PROMPT")).toString())
                            webSocket.send(JSONObject().put("type", "registered").put("id", id).put("payload", JSONObject().put("client-key", "key-123")).toString())
                        }
                        "request" -> {
                            val input = message.getJSONObject("payload").optString("inputId")
                            val payload = when (message.getString("uri")) {
                                "ssap://com.webos.service.connectionmanager/getinfo" -> JSONObject()
                                    .put("returnValue", true)
                                    .apply {
                                        fun adapter(mac: String, name: String) = JSONObject().put("macAddress", mac)
                                            .apply { if (statesInInfo) put("state", if (connectedAdapter == name) "connected" else "disconnected") }
                                        if (wired) put("wiredInfo", adapter("11:22:33:44:55:66", "wired"))
                                        put("wifiInfo", adapter("aa:bb:cc:dd:ee:ff", "wifi"))
                                    }
                                "ssap://com.webos.service.connectionmanager/getStatus" -> JSONObject()
                                    .put("returnValue", connectedAdapter != null)
                                    .put("wired", JSONObject().put("state", if (connectedAdapter == "wired") "connected" else "disconnected"))
                                    .put("wifi", JSONObject().put("state", if (connectedAdapter == "wifi") "connected" else "disconnected"))
                                else -> if (input == "HDMI_9") JSONObject().put("returnValue", false).put("errorText", "no such input") else JSONObject().put("returnValue", true)
                            }
                            webSocket.send(JSONObject().put("type", "response").put("id", id).put("payload", payload).toString())
                        }
                    }
                }
            }).build(),
        )
    }

    @Test
    fun registersAndReportsTheCertificate() = test {
        serveTv(prompt = true)
        var prompted = false
        client().use { client ->
            assertNull(client.certificate)
            assertEquals("key-123", client.connect(null, onPrompt = { prompted = true }))
            assertEquals(expectedPin, client.certificate)
            client.switchInput("HDMI_2")
        }
        assertTrue(prompted)
        val register = received.first { it.getString("type") == "register" }
        assertEquals("PROMPT", register.getJSONObject("payload").getString("pairingType"))
        assertTrue(register.getJSONObject("payload").has("manifest"))
        val switch = received.first { it.getString("type") == "request" }
        assertEquals("ssap://tv/switchInput", switch.getString("uri"))
        assertEquals("HDMI_2", switch.getJSONObject("payload").getString("inputId"))
    }

    @Test
    fun reportsOnlyTheConnectedAdaptersMacAddress() = test {
        serveTv(connectedAdapter = "wifi")
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
    }

    @Test
    fun reportsEveryMacAddressWhenTheTvCannotSayWhichIsInUse() = test {
        serveTv(connectedAdapter = null)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
    }

    @Test
    fun doesNotAskForStatusWhenGetinfoAlreadySaysWhichAdapterIsConnected() = test {
        serveTv(connectedAdapter = "wired", statesInInfo = true)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("11:22:33:44:55:66"), client.macAddresses())
        }
        assertEquals(emptyList<String>(), received.map { it.optString("uri") }.filter { it.endsWith("getStatus") })
    }

    @Test
    fun doesNotAskForStatusWhenOnlyOneAdapterHasAMacAddress() = test {
        serveTv(connectedAdapter = null, wired = false)
        client().use { client ->
            client.connect(null)
            assertEquals(listOf("aa:bb:cc:dd:ee:ff"), client.macAddresses())
        }
        assertEquals(emptyList<String>(), received.map { it.optString("uri") }.filter { it.endsWith("getStatus") })
    }

    @Test
    fun aCancelledLookupStopsInsteadOfReturningAnEmptyList() = test {
        serveTv(delayMs = 600)
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
    fun sendsTheStoredKeyWhenReconnecting() = test {
        serveTv()
        client(pinned = expectedPin).use { it.connect("key-123") }
        assertEquals("key-123", received.first().getJSONObject("payload").getString("client-key"))
    }

    @Test
    fun refusesATvWhoseKeyDoesNotMatchThePin() = test {
        // No response is enqueued: the handshake must fail before any request reaches the server.
        val error = runCatching { client(pinned = "00".repeat(32)).use { it.connect("key-123") } }.exceptionOrNull()
        assertTrue("got $error", error is LgTvException && error.permanent && error.message!!.contains("certificate"))
        assertEquals(0, received.size)
    }

    @Test
    fun aRegistrationTheTvRefusesIsPermanent() = test {
        serveTv(refuseRegistration = true)
        val error = runCatching { client().use { it.connect("key-123") } }.exceptionOrNull()
        assertTrue("got $error", error is LgTvException && error.permanent && error.message!!.contains("403 cancelled"))
    }

    @Test
    fun requestFailuresCarryTheTvsMessage() = test {
        serveTv()
        client().use { client ->
            client.connect(null)
            val error = runCatching { client.switchInput("HDMI_9") }.exceptionOrNull()
            assertEquals("no such input", error?.message)
            assertFalse("a failed request is worth retrying", (error as LgTvException).permanent)
        }
    }

    @Test
    fun slowRepliesAreNotCutOffByTheHandshakeTimeout() = test {
        serveTv(delayMs = 700)
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
