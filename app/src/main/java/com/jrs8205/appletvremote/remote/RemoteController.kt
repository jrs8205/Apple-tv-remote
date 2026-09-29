package com.jrs8205.appletvremote.remote

import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.LgTvRepository
import com.jrs8205.appletvremote.data.LgTvSettings
import com.jrs8205.appletvremote.lgtv.LgTvClient
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.discovery.NetworkTargets
import com.jrs8205.appletvremote.discovery.NsdDiscovery
import com.jrs8205.appletvremote.discovery.WakeOnLan
import com.jrs8205.appletvremote.protocol.companion.CompanionException
import com.jrs8205.appletvremote.protocol.companion.ClientInfo
import com.jrs8205.appletvremote.protocol.companion.CompanionClient
import com.jrs8205.appletvremote.protocol.companion.CompanionConnection
import com.jrs8205.appletvremote.protocol.companion.CompanionEvent
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.MediaCapabilities
import com.jrs8205.appletvremote.protocol.companion.MediaCommand
import com.jrs8205.appletvremote.protocol.companion.PairingSession
import com.jrs8205.appletvremote.protocol.companion.SocketConnector
import com.jrs8205.appletvremote.protocol.companion.SystemStatus
import com.jrs8205.appletvremote.protocol.companion.TouchPhase
import com.jrs8205.appletvremote.protocol.companion.TouchPump
import com.jrs8205.appletvremote.protocol.companion.TouchSample
import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import com.jrs8205.appletvremote.protocol.textinput.TextInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteState(
    val device: PairedDevice? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val systemStatus: SystemStatus = SystemStatus.UNKNOWN,
    val media: MediaCapabilities = MediaCapabilities(0),
    val keyboard: TextInputState? = null,
    val wakingTv: Boolean = false,
    val lastError: Throwable? = null,
)

/** One pairing conversation with a TV, from PIN prompt to credentials; opaque outside [RemoteController]. */
class PairingAttempt internal constructor(
    internal val device: DiscoveredDevice,
    internal val identity: ControllerIdentity,
    internal val connection: CompanionConnection,
    internal val session: PairingSession,
)

/**
 * The app's single owner of the Apple TV session. UI actions become ordered commands on one
 * queue; events from the TV fold into [state].
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val deviceRepository: DeviceRepository,
    private val identityRepository: IdentityRepository,
    private val connector: SocketConnector,
    private val networkTargets: NetworkTargets,
    private val discovery: NsdDiscovery,
    private val lgTvRepository: LgTvRepository,
    private val clientName: String,
    private val clientModel: String,
    val log: ConnectionLog,
) {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val clientMutex = Mutex()
    private var client: CompanionClient? = null
    private var clientDevice: PairedDevice? = null
    private var eventJob: Job? = null
    private var backgroundDisconnect: Job? = null
    @Volatile private var pairing: PairingAttempt? = null

    private val commands = Channel<suspend CompanionClient.() -> Unit>(Channel.UNLIMITED)
    private val touchRecovery = AtomicBoolean(false)
    /** A power tap is queued behind a connect attempt; a failure of that attempt must not spend 6 s on mDNS first. */
    private val powerDecisionPending = AtomicBoolean(false)
    private val touchPump = TouchPump(scope) { sample ->
        val active = currentClient() ?: return@TouchPump
        try {
            active.touch(sample.phase, sample.x, sample.y)
        } catch (e: CompanionException.ConnectionClosed) {
            // Touch samples bypass the command queue, so a dead connection is handed to it for address recovery.
            if (touchRecovery.compareAndSet(false, true)) {
                enqueue {
                    try {
                        ensureConnected()
                    } finally {
                        touchRecovery.set(false)
                    }
                }
            }
            throw e
        }
    }

    init {
        scope.launch {
            deviceRepository.device.collect { device ->
                _state.update { it.copy(device = device) }
                if (device == null) dropClient()
            }
        }
        scope.launch {
            for (command in commands) {
                val active = currentClient() ?: continue
                try {
                    active.command()
                    _state.update { it.copy(lastError = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log { "command failed: $e" }
                    _state.update { it.copy(lastError = e) }
                    if (e is CompanionException.ConnectionClosed && (_state.value.wakingTv || powerDecisionPending.get())) {
                        // The wake-up waiting behind this command re-resolves the address itself; recovering here would only delay it.
                        log.log { "skipping address recovery: ${if (_state.value.wakingTv) "wake-up" else "power tap"} queued" }
                    } else if (e is CompanionException.ConnectionClosed) {
                        try {
                            recover(command)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.log { "recovery failed: $e" }
                        }
                    }
                }
            }
        }
    }

    /**
     * The Apple TV picks a new port on every boot and may get a new address, so a failed connect
     * re-resolves it over mDNS and repeats the command once. A TV that is asleep stays unreachable
     * until the power button wakes it through the LG TV.
     */
    private suspend fun recover(command: suspend CompanionClient.() -> Unit) {
        val device = _state.value.device ?: return
        if (!refreshAddress(device)) return
        val client = currentClient() ?: return
        try {
            client.command()
            _state.update { it.copy(lastError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log { "command failed after recovery: $e" }
        }
    }

    /** Looks the TV up by name for a few seconds; stores and returns true when its address or port changed. */
    private suspend fun refreshAddress(device: PairedDevice): Boolean {
        val found = discover(ADDRESS_REFRESH_MS) { list -> list.firstOrNull { it.serviceName == device.name } }
        if (found == null) {
            log.log { "${device.name} not resolved by mDNS within ${ADDRESS_REFRESH_MS / 1000} s" }
            return false
        }
        if (found.host == device.host && found.port == device.port) {
            log.log { "address unchanged: ${found.host}:${found.port}" }
            return false
        }
        log.log { "address changed to ${found.host}:${found.port}" }
        deviceRepository.updateAddress(found.host, found.port)
        withTimeoutOrNull(2000) { _state.first { it.device?.host == found.host && it.device?.port == found.port } }
        return true
    }

    /** Watches discovery for up to [timeoutMs] until [pick] yields a value; null on timeout or when discovery cannot start. */
    private suspend fun <T : Any> discover(timeoutMs: Long, pick: (List<DiscoveredDevice>) -> T?): T? = try {
        withTimeoutOrNull(timeoutMs) { discovery.devices().mapNotNull(pick).first() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log { "discovery failed: $e" }
        null
    }

    /**
     * Wakes the chain: the LG TV is switched on over the network and told to select the Apple
     * TV's HDMI input, which wakes the Apple TV through HDMI-CEC; then connect attempts repeat
     * while everything boots.
     */
    fun wakeAndConnect() {
        val lg = lgTvRepository.settings
        _state.update { it.copy(wakingTv = true) }
        enqueue {
            val started = System.currentTimeMillis()
            try {
                val settings = lg.first()
                log.log {
                    val lgPart = if (settings.enabled && settings.host.isNotBlank()) "LG ${settings.host}, input ${settings.inputId}, MAC ${settings.macAddress ?: "unknown"}" else "no LG TV"
                    "wake-up started: $lgPart, Apple TV ${_state.value.device?.let { "${it.host}:${it.port}" } ?: "not paired"}"
                }
                if (settings.enabled && settings.host.isNotBlank()) wakeThroughLgTv(settings)
                var attempt = 0
                // The address and port may change while the TV boots, so every attempt asks for the client afresh.
                while (true) {
                    try {
                        requireClient().ensureConnected()
                        break
                    } catch (e: CompanionException) {
                        log.log { "Apple TV attempt ${attempt + 1}/$WAKE_CONNECT_ATTEMPTS failed: $e" }
                        if (++attempt >= WAKE_CONNECT_ATTEMPTS) throw e
                        delay(WAKE_RETRY_DELAY_MS)
                        refreshAddress(_state.value.device ?: throw e)
                    }
                }
                requireClient().pressButton(HidButton.WAKE)
                _state.update { it.copy(systemStatus = SystemStatus.AWAKE) }
                log.log { "wake-up done in ${(System.currentTimeMillis() - started) / 1000} s" }
            } catch (e: Exception) {
                if (e !is CancellationException) log.log { "wake-up failed after ${(System.currentTimeMillis() - started) / 1000} s: $e" }
                throw e
            } finally {
                _state.update { it.copy(wakingTv = false) }
            }
        }
    }

    /**
     * Turns the LG TV on and selects the Apple TV's input. The magic packet goes out before every
     * connection attempt until the TV's socket answers: on a Wi-Fi mesh the phone's ARP lookup of the
     * sleeping TV succeeds only now and then, and a packet sent while it fails never leaves the phone.
     */
    private suspend fun wakeThroughLgTv(settings: LgTvSettings) {
        val macs = settings.macAddress?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val targets = if (macs.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            networkTargets.broadcastAddresses() + listOfNotNull(runCatching { InetAddress.getByName(settings.host) }.getOrNull())
        }
        var lastFailure: String? = null
        val outcome = WakeRetry(LG_WAKE_TIMEOUT_MS, LG_RETRY_DELAY_MS).run(
            sendWake = { attempt -> if (macs.isNotEmpty()) sendWakeOnLan(macs, targets, describe = attempt == 1) },
            connect = {
                try {
                    lgTvClient(settings).use { client ->
                        client.register(settings)
                        client.switchInput(settings.inputId)
                    }
                } catch (e: Exception) {
                    // The same failure repeats every second while the TV boots; only a change in it is news.
                    if (e !is CancellationException && e.toString() != lastFailure) {
                        lastFailure = e.toString()
                        log.log { "LG TV attempt failed: $e" }
                    }
                    throw e
                }
            },
        )
        val error = outcome.error
        if (error != null) {
            log.log { "LG TV did not respond after ${outcome.attempts} attempts: $error" }
            return
        }
        log.log { "LG TV switched to ${settings.inputId} on attempt ${outcome.attempts}" }
        delay(LG_CEC_SETTLE_MS)
    }

    /** One burst of magic packets to every target; only the first burst is described in the log, failures always are. */
    private suspend fun sendWakeOnLan(macs: List<String>, targets: List<InetAddress>, describe: Boolean) = withContext(Dispatchers.IO) {
        var delivered = 0
        val failures = LinkedHashSet<String>()
        repeat(3) {
            for (mac in macs) {
                runCatching { WakeOnLan.send(mac, targets, bind = networkTargets::bindToLan) }
                    .onSuccess { failures += it.failures; delivered += it.delivered }
                    .onFailure { failures += it.toString() }
            }
            delay(250)
        }
        if (describe) {
            log.log {
                val lan = if (networkTargets.lanAvailable()) "bound to Wi-Fi" else "no Wi-Fi or Ethernet network to bind to"
                "sent wake-on-lan to the LG TV: ${macs.size} addresses, $delivered packets via ${targets.joinToString { it.hostAddress }}, $lan"
            }
        }
        failures.forEach { failure -> log.log { "wake-on-lan send failed: $failure" } }
    }

    /**
     * Pairs with the LG TV: the TV shows a prompt, the key it returns is stored together with the
     * TV's certificate, which later connections insist on. Also learns its MAC.
     */
    suspend fun pairLgTv(host: String, onPrompt: () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            LgTvClient(host, log, pinnedCertificate = null, socketFactory = networkTargets.lanSocketFactory()).use { client ->
                val key = client.connect(null, onPrompt)
                lgTvRepository.setHost(host)
                lgTvRepository.setClientKey(key)
                lgTvRepository.setCertificate(client.certificate)
                client.macAddresses().takeIf { it.isNotEmpty() }?.let { lgTvRepository.setMacAddress(it.joinToString(",")) }
            }
            Unit
        }
    }

    /** Sends the TV to standby; HDMI-CEC puts the Apple TV to sleep with it. */
    suspend fun turnOffLgTv(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = lgTvRepository.settings.first()
            if (!settings.enabled || settings.host.isBlank()) throw IllegalStateException("LG TV not configured")
            lgTvClient(settings).use { client ->
                client.register(settings)
                client.turnOff()
            }
        }
    }

    private fun lgTvClient(settings: LgTvSettings) =
        LgTvClient(settings.host, log, pinnedCertificate = settings.certificate, socketFactory = networkTargets.lanSocketFactory())

    /** Registers with the stored key; a TV paired before certificates were recorded gets its key pinned on this first use. */
    private suspend fun LgTvClient.register(settings: LgTvSettings) {
        connect(settings.clientKey, timeoutMs = 15_000)
        if (settings.certificate == null) certificate?.let { lgTvRepository.setCertificate(it) }
    }

    fun press(button: HidButton, holdMs: Long = 0) = enqueue {
        log.log { if (holdMs > 0) "hold $button for $holdMs ms" else "press $button" }
        pressButton(button, holdMs)
    }

    fun media(command: MediaCommand) = enqueue {
        log.log { "media $command" }
        media(command)
    }

    fun skip(seconds: Double) = enqueue {
        log.log { "skip $seconds s" }
        skip(seconds)
    }

    fun togglePower() {
        val current = _state.value
        if (current.wakingTv) {
            log.log { "power tap ignored: wake-up already in progress" }
            return
        }
        when (current.connection) {
            ConnectionState.Ready -> {
                log.log { "power tap while connected, TV status ${current.systemStatus}" }
                enqueue { togglePowerOrWake() }
            }
            ConnectionState.Connecting -> {
                // The queue runs this after the connect attempt in flight, so the TV has by then answered or not.
                log.log { "power tap while connecting: deciding when the connect attempt ends" }
                powerDecisionPending.set(true)
                enqueue {
                    powerDecisionPending.set(false)
                    val after = _state.value
                    when {
                        after.wakingTv -> log.log { "power tap dropped: wake-up already in progress" }
                        after.connection == ConnectionState.Ready -> togglePowerOrWake()
                        else -> {
                            log.log { "connect attempt ended in ${after.connection}: waking the chain" }
                            wakeAndConnect()
                        }
                    }
                }
            }
            else -> {
                log.log { "power tap while ${current.connection}: waking the chain" }
                wakeAndConnect()
            }
        }
    }

    private suspend fun CompanionClient.togglePowerOrWake() {
        try {
            togglePowerConnected()
        } catch (e: CompanionException) {
            // The socket looked open but the TV had gone to sleep behind it: treat this as a wake request.
            log.log { "power command failed on a stale connection, waking instead: $e" }
            wakeAndConnect()
        }
    }

    private suspend fun CompanionClient.togglePowerConnected() {
        // A TV that answers our session is awake unless it told us otherwise, so an unknown state means sleep.
        val status = fetchAttentionState() ?: _state.value.systemStatus
        val button = if (status == SystemStatus.ASLEEP) HidButton.WAKE else HidButton.SLEEP
        log.log { "TV status $status, pressing $button" }
        pressButton(button)
        _state.update { it.copy(systemStatus = if (button == HidButton.WAKE) SystemStatus.AWAKE else SystemStatus.ASLEEP) }
    }

    fun touch(phase: TouchPhase, x: Int, y: Int) = touchPump.submit(TouchSample(phase, x, y))

    /** Replaces the text in the focused field on the TV. */
    fun sendText(text: String) = enqueue {
        // Only the length: the text may be a password typed into the TV.
        log.log { "send text, ${text.length} characters" }
        sendText(text, replace = true)
    }

    /** Asks the TV whether a text field is focused and updates [RemoteState.keyboard]. */
    fun refreshKeyboard() = enqueue {
        val state = textInputState()
        _state.update { it.copy(keyboard = state) }
    }

    fun connect() = enqueue { ensureConnected() }

    fun onAppForeground() {
        backgroundDisconnect?.cancel()
        backgroundDisconnect = null
        val device = _state.value.device
        // The paired TV is still being read from disk when the app starts, so a missing device says nothing about pairing yet.
        log.log { "app in foreground, ${if (device == null) "no paired TV loaded yet" else "connection ${_state.value.connection}"}" }
        if (device != null) connect()
    }

    fun onAppBackground(keepAlive: Boolean) {
        log.log { if (keepAlive) "app in background, keeping the connection for the media notification" else "app in background, disconnecting in ${BACKGROUND_DISCONNECT_MS / 1000} s" }
        if (keepAlive) return
        backgroundDisconnect?.cancel()
        backgroundDisconnect = scope.launch {
            delay(BACKGROUND_DISCONNECT_MS)
            log.log { "background disconnect" }
            clientMutex.withLock { client?.disconnect() }
        }
    }

    suspend fun forget() {
        dropClient()
        deviceRepository.forget()
    }

    /** Opens a connection to [device] and asks it to show its PIN; [finishPairing] and [cancelPairing] take the returned attempt. */
    suspend fun startPairing(device: DiscoveredDevice): PairingAttempt {
        cancelCurrentPairing()
        val identity = ControllerIdentity(
            pairingId = UUID.randomUUID().toString(),
            signingKey = Ed25519KeyPair.generate(SecureRandomSource),
            displayName = clientName,
        )
        val connection = CompanionConnection(connector, device.host, device.port, log)
        log.log { "pairing started with ${device.serviceName} at ${device.host}:${device.port} (${device.model})" }
        try {
            connection.open()
            val session = PairingSession(connection, identity, SecureRandomSource)
            session.start()
            return PairingAttempt(device, identity, connection, session).also { pairing = it }
        } catch (e: Throwable) {
            // Also on cancellation: nothing else holds this connection yet.
            withContext(NonCancellable) { connection.close() }
            throw e
        }
    }

    /**
     * Completes [attempt] with the PIN. The attempt is spent either way: the TV drops its pair-setup after
     * a wrong PIN, so a retry needs a fresh [startPairing].
     */
    suspend fun finishPairing(attempt: PairingAttempt, pin: String): PairedDevice {
        val credentials: Credentials = try {
            attempt.session.finish(pin)
        } finally {
            withContext(NonCancellable) { release(attempt) }
        }
        val device = PairedDevice(attempt.device.serviceName, attempt.device.host, attempt.device.port, credentials)
        log.log { "paired with ${device.name}" }
        dropClient()
        deviceRepository.save(device)
        _state.update { it.copy(device = device) }
        connect()
        return device
    }

    /** Closes [attempt]; a newer attempt that has already replaced it is left alone. */
    suspend fun cancelPairing(attempt: PairingAttempt) = release(attempt)

    private suspend fun cancelCurrentPairing() {
        pairing?.let { release(it) }
    }

    private suspend fun release(attempt: PairingAttempt) {
        attempt.connection.close()
        if (pairing === attempt) pairing = null
    }

    private fun enqueue(command: suspend CompanionClient.() -> Unit) {
        commands.trySend(command)
    }

    private suspend fun requireClient(): CompanionClient = currentClient() ?: throw CompanionException.ConnectionClosed(null)

    private suspend fun currentClient(): CompanionClient? = clientMutex.withLock {
        val device = _state.value.device ?: deviceRepository.device.first() ?: return null
        client?.takeIf { device.sameAs(clientDevice) }?.let { return it }
        client?.let { stale -> scope.launch { stale.disconnect() } }
        eventJob?.cancel()
        val created = CompanionClient(
            connector = connector,
            host = device.host,
            port = device.port,
            credentials = device.credentials,
            clientInfo = ClientInfo(name = clientName, model = clientModel, publicId = identityRepository.publicId()),
            log = log,
        )
        client = created
        clientDevice = device
        log.log { "client for ${device.name} at ${device.host}:${device.port}" }
        eventJob = scope.launch {
            launch {
                created.state.collect { connection ->
                    if (connection != _state.value.connection) log.log { "connection: $connection" }
                    _state.update { it.copy(connection = connection) }
                }
            }
            created.events.collect { event ->
                when (event) {
                    is CompanionEvent.SystemStatusChanged -> {
                        if (event.status != _state.value.systemStatus) log.log { "TV status: ${event.status}" }
                        _state.update { it.copy(systemStatus = event.status) }
                    }
                    is CompanionEvent.MediaCapabilitiesChanged -> {
                        val media = event.capabilities
                        if (media != _state.value.media) log.log { "media flags 0x${media.flags.toString(16)}: ${media.playState}" }
                        _state.update { it.copy(media = media) }
                    }
                    is CompanionEvent.TextInputStarted -> _state.update { it.copy(keyboard = event.state ?: TextInputState(null, null, null)) }
                    CompanionEvent.TextInputStopped -> _state.update { it.copy(keyboard = null) }
                    else -> Unit
                }
            }
        }
        created
    }

    private suspend fun dropClient() {
        clientMutex.withLock {
            val stale = client ?: return
            client = null
            clientDevice = null
            eventJob?.cancel()
            eventJob = null
            log.log { "dropping the connection" }
            stale.disconnect()
            _state.update { it.copy(connection = ConnectionState.Disconnected, systemStatus = SystemStatus.UNKNOWN, media = MediaCapabilities(0), keyboard = null) }
        }
    }

    private companion object {
        const val BACKGROUND_DISCONNECT_MS = 30_000L
        const val WAKE_RETRY_DELAY_MS = 4_000L
        const val WAKE_CONNECT_ATTEMPTS = 6
        const val ADDRESS_REFRESH_MS = 6_000L
        const val LG_WAKE_TIMEOUT_MS = 90_000L
        const val LG_RETRY_DELAY_MS = 1_000L
        const val LG_CEC_SETTLE_MS = 3_000L
    }
}
