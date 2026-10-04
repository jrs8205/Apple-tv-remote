package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.AuthenticationFailedException
import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import com.jrs8205.appletvremote.protocol.opack.Opack
import com.jrs8205.appletvremote.protocol.opack.OpackException
import com.jrs8205.appletvremote.protocol.pairing.SessionKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class IncomingEvent(val name: String, val content: Map<*, *>)

sealed class CompanionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Timeout(name: String) : CompanionException("no response to $name")
    class Remote(name: String, val remoteMessage: String) : CompanionException("$name failed: $remoteMessage")
    class ConnectionClosed(cause: Throwable?) : CompanionException("connection closed${cause?.let { ": ${it.message}" } ?: ""}", cause)
    class Protocol(detail: String) : CompanionException(detail)
}

/**
 * One TCP connection to an Apple TV. Frames are read on a background coroutine; sends are
 * serialized so the cipher counter matches the byte order on the wire. Requests are matched to
 * responses by `_x`; pairing replies are matched by frame type.
 */
class CompanionConnection(
    private val connector: SocketConnector,
    private val host: String,
    private val port: Int,
    private val log: ProtocolLog = ProtocolLog.None,
    private val requestTimeoutMs: Long = 5000,
    private val connectTimeoutMs: Int = 5000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    random: RandomSource = SecureRandomSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val xid = AtomicLong(random.nextBytes(2).let { ((it[0].toLong() and 0xFF) shl 8) or (it[1].toLong() and 0xFF) })
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<Map<*, *>>>()
    private val pendingPairing = ConcurrentHashMap<FrameType, CompletableDeferred<Map<*, *>>>()
    private val closedSignal = CompletableDeferred<Throwable?>()
    private val shutDown = AtomicBoolean(false)
    private val _events = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<IncomingEvent> = _events

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var cipher: FrameCipher? = null
    @Volatile var isOpen: Boolean = false
        private set

    suspend fun open() {
        check(socket == null) { "connection already opened" }
        val connected = try {
            withContext(ioDispatcher) {
                // A cancelled caller never sees the socket, so it would leak without this.
                connector.connect(host, port, connectTimeoutMs).also { if (!isActive) it.close() }
            }
        } catch (e: IOException) {
            shutdown(e)
            throw CompanionException.ConnectionClosed(e)
        }
        socket = connected
        output = BufferedOutputStream(connected.getOutputStream())
        isOpen = true
        val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
        scope.launch { readLoop(input) }
    }

    suspend fun close() {
        shutdown(null)
    }

    /** Resolves once the connection is gone: null after a deliberate or peer close, the failure otherwise. */
    suspend fun awaitClosed(): Throwable? = closedSignal.await()

    fun installCipher(keys: SessionKeys) {
        cipher = FrameCipher(encryptKey = keys.encryptKey, decryptKey = keys.decryptKey)
    }

    suspend fun pairingExchange(sendType: FrameType, payload: Map<String, Any?>, expectType: FrameType): Map<*, *> {
        ensureOpen()
        val reply = CompletableDeferred<Map<*, *>>()
        pendingPairing[expectType] = reply
        try {
            send(sendType, LinkedHashMap(payload).apply { put("_x", xid.getAndIncrement()) })
            return withTimeoutOrNull(requestTimeoutMs) { reply.await() } ?: run {
                shutdown(null)
                throw CompanionException.Timeout("pairing frame $sendType")
            }
        } finally {
            pendingPairing.remove(expectType)
        }
    }

    /** Sends a `_t=2` request and returns the `_c` content of the matching response. */
    suspend fun request(name: String, content: Map<String, Any?> = emptyMap()): Map<*, *> {
        ensureOpen()
        val id = xid.getAndIncrement()
        val reply = CompletableDeferred<Map<*, *>>()
        pendingRequests[id] = reply
        try {
            send(FrameType.E_OPACK, linkedMapOf("_i" to name, "_t" to REQUEST, "_c" to content, "_x" to id))
            val message = withTimeoutOrNull(requestTimeoutMs) { reply.await() } ?: run {
                log.log { "no response to $name within $requestTimeoutMs ms, closing" }
                shutdown(null)
                throw CompanionException.Timeout(name)
            }
            (message["_em"] as? String)?.let { throw CompanionException.Remote(name, it) }
            return message["_c"] as? Map<*, *> ?: emptyMap<Any, Any>()
        } finally {
            pendingRequests.remove(id)
        }
    }

    /** Sends a `_t=1` event; nothing is awaited. */
    suspend fun event(name: String, content: Map<String, Any?> = emptyMap()) {
        ensureOpen()
        send(FrameType.E_OPACK, linkedMapOf("_i" to name, "_t" to EVENT, "_c" to content, "_x" to xid.getAndIncrement()))
    }

    private fun ensureOpen() {
        if (!isOpen) throw CompanionException.ConnectionClosed(closedSignal.takeIf { it.isCompleted }?.getCompleted())
    }

    private suspend fun send(type: FrameType, message: Map<String, Any?>) {
        val out = output ?: throw CompanionException.ConnectionClosed(null)
        val plain = Opack.encode(message)
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val bytes = cipher?.seal(type, plain) ?: (FrameCodec.encodeHeader(type, plain.size) + plain)
                // A write blocks once the TV stops reading and the socket buffers are full; it cannot be cancelled, and it
                // would hold every later command behind it, so a write that does not finish in time closes the socket.
                val watchdog = scope.launch {
                    delay(requestTimeoutMs)
                    log.log { "a write did not finish within $requestTimeoutMs ms, closing" }
                    shutdown(SocketTimeoutException("write did not finish within $requestTimeoutMs ms"))
                }
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: IOException) {
                    shutdown(e)
                    throw CompanionException.ConnectionClosed(e)
                } finally {
                    watchdog.cancel()
                }
            }
        }
    }

    private fun readLoop(input: DataInputStream) {
        var failure: Throwable? = null
        try {
            while (true) handle(FrameCodec.readFrame(input))
        } catch (_: EOFException) {
            log.log { "peer closed the connection" }
        } catch (e: IOException) {
            if (isOpen) failure = e
        } catch (e: AuthenticationFailedException) {
            failure = e
        } catch (e: FrameException) {
            failure = e
        } catch (e: RuntimeException) {
            // A malformed frame must end this connection, never the process.
            failure = e
        } finally {
            failure?.let { cause -> log.log { "connection lost: $cause" } }
            shutdown(failure)
        }
    }

    private fun handle(frame: RawFrame) {
        val body = cipher?.takeIf { frame.payload.isNotEmpty() }?.open(frame.header, frame.payload) ?: frame.payload
        val message = try {
            Opack.decode(body) as? Map<*, *>
        } catch (e: OpackException) {
            log.log { "undecodable ${frame.type} frame: ${e.message}" }
            null
        } ?: return
        when (frame.type) {
            FrameType.PS_START, FrameType.PS_NEXT, FrameType.PV_START, FrameType.PV_NEXT ->
                pendingPairing.remove(frame.type)?.complete(message) ?: log.log { "unexpected pairing frame ${frame.type}" }
            FrameType.U_OPACK, FrameType.E_OPACK, FrameType.P_OPACK -> dispatch(message)
            else -> log.log { "ignoring frame type ${frame.type}" }
        }
    }

    private fun dispatch(message: Map<*, *>) {
        when (message["_t"]) {
            RESPONSE -> {
                val id = message["_x"] as? Long
                val waiter = id?.let(pendingRequests::remove)
                if (waiter == null) log.log { "response with unknown xid $id" } else waiter.complete(message)
            }
            EVENT -> {
                val name = message["_i"] as? String ?: return
                _events.tryEmit(IncomingEvent(name, message["_c"] as? Map<*, *> ?: emptyMap<Any, Any>()))
            }
            else -> log.log { "ignoring message type ${message["_t"]} (${message["_i"]})" }
        }
    }

    private fun shutdown(cause: Throwable?) {
        if (!shutDown.compareAndSet(false, true)) return
        isOpen = false
        runCatching { socket?.close() }
        val error = CompanionException.ConnectionClosed(cause)
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
        pendingPairing.values.forEach { it.completeExceptionally(error) }
        pendingPairing.clear()
        closedSignal.complete(cause)
    }

    private companion object {
        const val EVENT = 1L
        const val REQUEST = 2L
        const val RESPONSE = 3L
    }
}
