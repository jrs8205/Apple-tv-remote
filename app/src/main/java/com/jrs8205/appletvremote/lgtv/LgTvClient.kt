package com.jrs8205.appletvremote.lgtv

import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class LgTvException(message: String) : RuntimeException(message)

/**
 * One WebSocket session with an LG webOS TV. Newer sets only accept `wss` on port 3001 with a
 * self-signed certificate, so trust is pinned to the key seen when pairing: [pinnedCertificate] is
 * that SPKI SHA-256, or null to accept whatever the TV presents and report it as [certificate].
 * [socketFactory] keeps the connection on the LAN when the phone's default route is mobile data.
 */
class LgTvClient(
    private val host: String,
    private val log: ProtocolLog = ProtocolLog.None,
    pinnedCertificate: String? = null,
    socketFactory: SocketFactory? = null,
    private val port: Int = 3001,
    private val openTimeoutMs: Long = 4_000,
    handshakeTimeoutMs: Long = 5_000,
) : AutoCloseable {

    private val trust = LgTvTrust(pinnedCertificate)
    private val http = shared.newBuilder()
        .apply { if (socketFactory != null) socketFactory(socketFactory) }
        // Bounds the TLS and upgrade handshake only: OkHttp lifts the read timeout once the socket is a WebSocket.
        .readTimeout(handshakeTimeoutMs, TimeUnit.MILLISECONDS)
        .sslSocketFactory(sslContext(trust).socketFactory, trust)
        // The pin identifies the TV; its self-signed certificate carries no usable host name.
        .hostnameVerifier { _, _ -> true }
        .build()

    private val ids = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<LgTvMessages.Incoming>>()
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<String>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var pairingPrompted: (() -> Unit)? = null

    /** SPKI SHA-256 of the certificate the TV presented, once connected. */
    val certificate: String? get() = trust.seen

    /**
     * Connects and registers. With a stored [clientKey] this completes silently; without one the
     * TV shows a prompt, [onPrompt] fires, and the returned key must be stored for next time.
     */
    suspend fun connect(clientKey: String?, onPrompt: () -> Unit = {}, timeoutMs: Long = 60_000): String {
        pairingPrompted = onPrompt
        open()
        val id = "register_${ids.getAndIncrement()}"
        val reply = exchange(id, LgTvMessages.register(id, clientKey), timeoutMs)
        return reply.payload?.optString("client-key")?.takeIf { it.isNotEmpty() }
            ?: throw LgTvException("TV did not return a client key (${reply.error ?: reply.type})")
    }

    suspend fun switchInput(inputId: String) {
        request("ssap://tv/switchInput", mapOf("inputId" to inputId))
    }

    suspend fun turnOff() {
        request("ssap://system/turnOff")
    }

    /**
     * The MAC address of the adapter the TV is connected through, which is the one Wake-on-LAN
     * must target. When the TV does not say which adapter is in use, every address it reports.
     */
    suspend fun macAddresses(): List<String> {
        val info = answered { request("ssap://com.webos.service.connectionmanager/getinfo") } ?: return emptyList()
        val adapters = listOf("wiredInfo" to "wired", "wifiInfo" to "wifi").mapNotNull { (infoKey, statusKey) ->
            val adapter = info.optJSONObject(infoKey) ?: return@mapNotNull null
            val mac = adapter.optString("macAddress").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Adapter(statusKey, mac, adapter.optString("state"))
        }
        // getStatus is a second round trip, worth it only when getinfo left the choice between adapters open.
        val status = if (adapters.size > 1 && adapters.any { it.state.isEmpty() }) {
            answered { request("ssap://com.webos.service.connectionmanager/getStatus") }
        } else {
            null
        }
        val connected = adapters.filter { adapter ->
            adapter.state.ifEmpty { status?.optJSONObject(adapter.statusKey)?.optString("state").orEmpty() }.equals("connected", ignoreCase = true)
        }
        return connected.ifEmpty { adapters }.map { it.mac }
    }

    private class Adapter(val statusKey: String, val mac: String, val state: String)

    /** A request the TV fails or does not answer counts as no answer; cancellation is not an answer and passes through. */
    private inline fun <T> answered(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun request(uri: String, payload: Map<String, Any?> = emptyMap(), timeoutMs: Long = 8_000): JSONObject {
        val id = ids.getAndIncrement().toString()
        val reply = exchange(id, LgTvMessages.request(id, uri, payload), timeoutMs)
        val body = reply.payload ?: JSONObject()
        if (reply.type == "error" || (body.has("returnValue") && !body.optBoolean("returnValue", true))) {
            throw LgTvException(reply.error ?: body.optString("errorText").ifEmpty { "$uri failed" })
        }
        return body
    }

    override fun close() {
        val current = socket ?: return
        socket = null
        if (opened.isCompleted) current.close(1000, null) else current.cancel()
    }

    private suspend fun open() {
        if (socket != null) return
        val request = Request.Builder().url("wss://$host:$port/").build()
        val current = http.newWebSocket(request, Listener())
        socket = current
        var established = false
        try {
            val failure = withTimeoutOrNull(openTimeoutMs) {
                select {
                    opened.onAwait { null }
                    closed.onAwait { it }
                }
            }
            established = failure == null && opened.isCompleted
            if (!established) {
                throw LgTvException(
                    when {
                        trust.rejected -> "the TV's certificate changed; pair with it again"
                        failure != null -> "could not reach the TV: $failure"
                        else -> "could not reach the TV: timeout"
                    },
                )
            }
        } finally {
            // Without this a half-open handshake keeps its thread and socket until OkHttp gives up on its own.
            if (!established) {
                current.cancel()
                socket = null
            }
        }
    }

    private suspend fun exchange(id: String, message: String, timeoutMs: Long): LgTvMessages.Incoming {
        val waiter = CompletableDeferred<LgTvMessages.Incoming>()
        waiters[id] = waiter
        try {
            (socket ?: throw LgTvException("not connected")).send(message)
            return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw LgTvException("no reply from the TV")
        } finally {
            waiters.remove(id)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val incoming = LgTvMessages.parse(text) ?: return
            val id = incoming.id ?: return
            if (incoming.type == "response" && incoming.payload?.optString("pairingType") == "PROMPT") {
                log.log { "LG TV asks for permission on its screen" }
                pairingPrompted?.invoke()
                return
            }
            waiters[id]?.complete(incoming)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.log { "LG TV socket failed: $t" }
            closed.complete(t.message ?: t.javaClass.simpleName)
            waiters.values.forEach { it.completeExceptionally(LgTvException("connection lost: ${t.message}")) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete("closed $code")
            waiters.values.forEach { it.completeExceptionally(LgTvException("connection closed")) }
        }
    }

    private companion object {
        /** One dispatcher and connection pool for every session; TLS trust differs per TV, so each instance derives its own client. */
        val shared: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build() }

        fun sslContext(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    }
}
