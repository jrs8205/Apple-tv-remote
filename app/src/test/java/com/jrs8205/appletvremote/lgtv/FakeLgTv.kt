package com.jrs8205.appletvremote.lgtv

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A webOS TV on loopback with a self-signed certificate; [serve] decides how the next connection is answered. */
class FakeLgTv : AutoCloseable {

    private val held = HeldCertificate.Builder().commonName("LG Smart TV").build()
    private val server = MockWebServer()
    private var serverSideClosed: CountDownLatch? = null

    /** SPKI SHA-256 of the certificate, the value a client pins after pairing. */
    val certificatePin: String = MessageDigest.getInstance("SHA-256").digest(held.keyPair.public.encoded).joinToString("") { "%02x".format(it) }
    val received = CopyOnWriteArrayList<JSONObject>()
    val hostName: String get() = server.hostName
    val port: Int get() = server.port

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    /**
     * Pairs on request (after a prompt when [prompt], or not at all when [refuseRegistration]) and answers
     * switchInput; [delayMs] holds every reply back. It reports a wired and a Wi-Fi adapter (only Wi-Fi
     * without [wired]); [connectedAdapter] is the one getStatus calls connected, and with [statesInInfo]
     * getinfo already carries those states itself. [inputs] are the external inputs it lists.
     */
    fun serve(
        delayMs: Long = 0,
        prompt: Boolean = false,
        connectedAdapter: String? = "wifi",
        wired: Boolean = true,
        statesInInfo: Boolean = false,
        refuseRegistration: Boolean = false,
        inputs: List<LgInput> = emptyList(),
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
                                "ssap://tv/getExternalInputList" -> JSONObject()
                                    .put("returnValue", true)
                                    .put("devices", JSONArray(inputs.map { JSONObject().put("id", it.id).put("label", it.label).put("connected", it.connected).put("appId", "com.webos.app.${it.id.lowercase()}") }))
                                else -> if (input == "HDMI_9") JSONObject().put("returnValue", false).put("errorText", "no such input") else JSONObject().put("returnValue", true)
                            }
                            webSocket.send(JSONObject().put("type", "response").put("id", id).put("payload", payload).toString())
                        }
                    }
                }
            }).build(),
        )
    }

    /** A client that closes properly completes the close handshake; the server must see it before shutting down. */
    fun assertClientClosed() {
        serverSideClosed?.let { assertTrue("the client never closed the socket", it.await(5, TimeUnit.SECONDS)) }
    }

    override fun close() = server.close()
}
