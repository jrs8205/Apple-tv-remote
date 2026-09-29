package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.PairVerify
import com.jrs8205.appletvremote.protocol.textinput.KeyedArchive
import com.jrs8205.appletvremote.protocol.textinput.TextInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session-level API over one paired Apple TV: connects lazily, verifies the pairing, starts the
 * remote-control session, subscribes to events and exposes typed commands.
 */
class CompanionClient(
    private val connector: SocketConnector,
    private val host: String,
    private val port: Int,
    private val credentials: Credentials,
    private val clientInfo: ClientInfo,
    private val random: RandomSource = SecureRandomSource,
    private val log: ProtocolLog = ProtocolLog.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Long = 5000,
    private val attentionTimeoutMs: Long = 2000,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Session(val connection: CompanionConnection, val sessionId: Long) {
        @Volatile var touchStartedAt: Long? = null
        var forwarder: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectMutex = Mutex()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _events = MutableSharedFlow<CompanionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var session: Session? = null

    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val events: SharedFlow<CompanionEvent> = _events

    suspend fun ensureConnected() {
        connectMutex.withLock {
            if (session?.connection?.isOpen == true) return
            connect()
        }
    }

    suspend fun disconnect() {
        connectMutex.withLock {
            val current = session
            session = null
            if (current == null) {
                _state.value = ConnectionState.Disconnected
                return
            }
            val connection = current.connection
            try {
                if (connection.isOpen) {
                    for (name in SUBSCRIBED_EVENTS) softly { connection.event("_interest", mapOf("_deregEvents" to listOf(name))) }
                    softly { withTimeoutOrNull(TEARDOWN_STEP_MS) { connection.request("_sessionStop", sessionArguments(current.sessionId)) } }
                    softly { withTimeoutOrNull(TEARDOWN_STEP_MS) { connection.request("_tiStop") } }
                    if (current.touchStartedAt != null) softly { withTimeoutOrNull(TEARDOWN_STEP_MS) { connection.request("_touchStop") } }
                }
            } finally {
                // The session is already gone, so the socket must go too, even when the caller was cancelled mid-teardown.
                withContext(NonCancellable) { connection.close() }
                current.forwarder?.cancel()
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    suspend fun pressButton(button: HidButton, holdMs: Long = 0) {
        val connection = connected()
        connection.request("_hidC", hidArguments(button, down = true))
        if (holdMs > 0) delay(holdMs)
        withContext(NonCancellable) { connection.request("_hidC", hidArguments(button, down = false)) }
    }

    suspend fun hid(button: HidButton, down: Boolean) {
        connected().request("_hidC", hidArguments(button, down))
    }

    suspend fun touch(phase: TouchPhase, x: Int, y: Int) {
        val current = connectedSession()
        val startedAt = current.touchStartedAt ?: run {
            current.connection.request("_touchStart", mapOf("_width" to 1000.0, "_height" to 1000.0, "_tFl" to 0L))
            clock().also { current.touchStartedAt = it }
        }
        val elapsed = (clock() - startedAt).coerceAtLeast(0)
        current.connection.event(
            "_hidT",
            linkedMapOf(
                "_ns" to elapsed,
                "_tFg" to 1L,
                "_cx" to x.coerceIn(0, 1000).toLong(),
                "_tPh" to phase.code.toLong(),
                "_cy" to y.coerceIn(0, 1000).toLong(),
            ),
        )
    }

    suspend fun media(command: MediaCommand) {
        connected().request("_mcc", mapOf("_mcc" to command.code.toLong()))
    }

    suspend fun skip(seconds: Double) {
        connected().request("_mcc", mapOf("_mcc" to MediaCommand.SKIP.code.toLong(), "_skpS" to seconds))
    }

    /**
     * The power state, or null when the TV does not answer. Some tvOS versions never reply, so this
     * uses its own short timeout and never tears the connection down.
     */
    suspend fun fetchAttentionState(): SystemStatus? = fetchAttentionState(connected())

    private suspend fun fetchAttentionState(connection: CompanionConnection): SystemStatus? =
        withTimeoutOrNull(attentionTimeoutMs) { parseStatus(connection.request("FetchAttentionState")) }

    /** Asks the TV about its keyboard; null when no text field is focused. */
    suspend fun textInputState(): TextInputState? = textInputState(connected())

    /**
     * Types into the focused field. A fresh `_tiStart` gives the current session id; with [replace]
     * the field is cleared first so the phone's text field stays the single source of truth.
     */
    suspend fun sendText(text: String, replace: Boolean) {
        val connection = connected()
        connection.request("_tiStop")
        val state = textInputState(connection) ?: throw CompanionException.Protocol("no text field is focused on the Apple TV")
        val session = state.sessionUuid ?: throw CompanionException.Protocol("keyboard session has no identifier")
        if (replace) connection.event("_tiC", mapOf("_tiV" to 1L, "_tiD" to KeyedArchive.clearOperation(session)))
        connection.event("_tiC", mapOf("_tiV" to 1L, "_tiD" to KeyedArchive.insertOperation(session, text)))
    }

    private suspend fun textInputState(connection: CompanionConnection): TextInputState? {
        val reply = connection.request("_tiStart")
        val archive = reply["_tiD"] as? ByteArray ?: return null
        return KeyedArchive.parseState(archive)
    }

    private suspend fun connected(): CompanionConnection = connectedSession().connection

    private suspend fun connectedSession(): Session {
        ensureConnected()
        return session ?: throw CompanionException.ConnectionClosed(null)
    }

    private suspend fun connect() {
        _state.value = ConnectionState.Connecting
        log.log { "connecting to $host:$port" }
        val connection = CompanionConnection(connector, host, port, log, requestTimeoutMs, ioDispatcher = ioDispatcher, random = random)
        var established: Session? = null
        try {
            connection.open()
            val verify = PairVerify(credentials, random)
            val m2 = connection.pairingExchange(FrameType.PV_START, verify.m1(), FrameType.PV_NEXT)
            val m4 = connection.pairingExchange(FrameType.PV_NEXT, verify.m3(m2), FrameType.PV_NEXT)
            connection.installCipher(verify.finish(m4))

            connection.request("_systemInfo", systemInfo())
            val localSid = random.nextBytes(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
            val startReply = connection.request("_sessionStart", sessionArguments(localSid))
            val remoteSid = startReply["_sid"] as? Long ?: throw CompanionException.Protocol("missing _sid in session start response")
            val current = Session(connection, (remoteSid shl 32) or localSid)
            established = current
            current.forwarder = scope.launch { connection.events.collect { forward(it) } }
            session = current

            // Registers with tvremoted; older tvOS rejects it, and the remote works either way.
            try {
                connection.request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2"))
            } catch (e: CompanionException.Remote) {
                log.log { "TVRCSessionStart rejected: ${e.remoteMessage}" }
            }

            val textInput = connection.request("_tiStart")
            (textInput["_tiD"] as? ByteArray)?.let { _events.tryEmit(CompanionEvent.TextInputStarted(textInput, KeyedArchive.parseState(it))) }
            for (name in SUBSCRIBED_EVENTS) connection.event("_interest", mapOf("_regEvents" to listOf(name)))
            val status = fetchAttentionState(connection)
            log.log { "session ready with $host:$port, TV status ${status ?: "unknown"}" }
            _state.value = ConnectionState.Ready
            if (status != null) _events.tryEmit(CompanionEvent.SystemStatusChanged(status))
            scope.launch { watchClose(current) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.log { "connect failed: $e" }
            connection.close()
            established?.forwarder?.cancel()
            if (session === established) session = null
            _state.value = ConnectionState.Failed(e)
            throw e
        }
    }

    private suspend fun watchClose(current: Session) {
        current.connection.awaitClosed()
        current.forwarder?.cancel()
        if (session === current) {
            session = null
            if (_state.value == ConnectionState.Ready) _state.value = ConnectionState.Disconnected
        }
    }

    private fun forward(event: IncomingEvent) {
        val typed = when (event.name) {
            "SystemStatus" -> CompanionEvent.SystemStatusChanged(parseStatus(event.content))
            "_iMC" -> CompanionEvent.MediaCapabilitiesChanged(MediaCapabilities.fromEvent(event.content))
            "_tiStarted" -> CompanionEvent.TextInputStarted(event.content, (event.content["_tiD"] as? ByteArray)?.let(KeyedArchive::parseState))
            "_tiStopped" -> CompanionEvent.TextInputStopped
            else -> CompanionEvent.Other(event.name, event.content)
        }
        _events.tryEmit(typed)
    }

    private fun systemInfo(): Map<String, Any?> = linkedMapOf(
        "_bf" to 0L,
        "_cf" to 512L,
        "_clFl" to 128L,
        "_i" to "cafecafecafe",
        "_idsID" to credentials.controller.pairingId.toByteArray(Charsets.UTF_8),
        "_pubID" to clientInfo.publicId,
        "_sf" to 256L,
        "_sv" to "170.18",
        "model" to clientInfo.model,
        "name" to clientInfo.name,
    )

    private fun sessionArguments(sid: Long): Map<String, Any?> = mapOf("_srvT" to SERVICE_TYPE, "_sid" to sid)

    private fun hidArguments(button: HidButton, down: Boolean): Map<String, Any?> =
        mapOf("_hidC" to button.code.toLong(), "_hBtS" to if (down) 1L else 2L)

    private fun parseStatus(content: Map<*, *>): SystemStatus = SystemStatus.fromCode((content["state"] as? Long)?.toInt() ?: 0)

    private suspend fun softly(step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log { "teardown step failed: $e" }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "com.apple.tvremoteservices"
        const val TEARDOWN_STEP_MS = 1000L
        val SUBSCRIBED_EVENTS = listOf("_iMC", "SystemStatus")
    }
}
