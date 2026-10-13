package com.miaouss90.tellocontroler.tello

import com.miaouss90.tellocontroler.controller.RcInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Tello SDK UDP transport: commands + acknowledgements on 8889, state on 8890. See docs/PROTOCOL.md.
 *
 * Acknowledged commands are serialized (one pending answer at a time). `emergency` and `rc` never wait,
 * and `land` preempts a pending acknowledgement instead of queueing behind it.
 */
class TelloClient(clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val HOST = "192.168.10.1"
        const val COMMAND_PORT = 8889
        const val STATE_PORT = 8890
        const val ACK_TIMEOUT_MS = 3_000L
        const val MOTION_ACK_TIMEOUT_MS = 20_000L
        const val MONITOR_PERIOD_MS = 250L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host by lazy { InetAddress.getByName(HOST) }
    private val responses = Channel<String>(Channel.UNLIMITED)
    private val ackLock = Mutex()
    private val stateMonitor = LinkMonitor(clock)

    @Volatile private var commandSocket: DatagramSocket? = null
    @Volatile private var stateSocket: DatagramSocket? = null
    @Volatile private var socketBinder: ((DatagramSocket) -> Unit)? = null

    private val _telemetry = MutableStateFlow(TelloTelemetry())
    val telemetry: StateFlow<TelloTelemetry> = _telemetry.asStateFlow()

    private val _connection = MutableStateFlow(TelloConnectionState.DISCONNECTED)
    val connection: StateFlow<TelloConnectionState> = _connection.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _stateLink = MutableStateFlow(LinkQuality.NONE)
    val stateLink: StateFlow<LinkQuality> = _stateLink.asStateFlow()

    init {
        scope.launch { monitorLink() }
    }

    /** Enters SDK mode; CONNECTED only after the Tello acknowledges `command` with `ok`. Also reconnects. */
    fun connect() {
        val state = _connection.value
        if (state == TelloConnectionState.CONNECTING || state == TelloConnectionState.CONNECTED) return
        _connection.value = TelloConnectionState.CONNECTING
        scope.launch {
            try {
                openSockets()
                if (request("command", ACK_TIMEOUT_MS) == CommandResult.Ok) {
                    stateMonitor.restart()
                    _connection.value = TelloConnectionState.CONNECTED
                    request("streamon", ACK_TIMEOUT_MS)
                } else {
                    _connection.value = TelloConnectionState.ERROR
                }
            } catch (_: Exception) {
                _connection.value = TelloConnectionState.ERROR
            }
        }
    }

    /**
     * Routes all Tello sockets through a specific network (see TelloWifiManager). Existing sockets are
     * closed: they may be bound to a network that no longer exists.
     */
    fun useNetwork(binder: ((DatagramSocket) -> Unit)?) {
        socketBinder = binder
        closeSockets()
        if (_connection.value == TelloConnectionState.CONNECTED) _connection.value = TelloConnectionState.LINK_LOST
    }

    /** The Tello Wi-Fi disappeared: nothing can reach the aircraft any more. */
    fun onNetworkLost() {
        closeSockets()
        val state = _connection.value
        if (state == TelloConnectionState.CONNECTED || state == TelloConnectionState.CONNECTING) {
            _connection.value = TelloConnectionState.LINK_LOST
        }
    }

    /**
     * Sent synchronously on the caller's thread (the RC loop): packets leave in order and evenly spaced.
     * Launching one coroutine per packet let 20 Hz commands bunch up or overtake each other (jerky flight).
     */
    fun rc(input: RcInput) {
        runCatching { sendRaw(TelloCommands.rc(input.roll, input.pitch, input.throttle, input.yaw)) }
    }

    suspend fun takeoff() = request("takeoff", MOTION_ACK_TIMEOUT_MS)

    suspend fun land() = request("land", MOTION_ACK_TIMEOUT_MS, preempt = true)

    /** SDK `stop`: hovers in place and cancels any motion in progress. Not guarded: it can only make the drone stay still. */
    suspend fun stop() = request("stop", ACK_TIMEOUT_MS)

    /** Mission Pad detection with the downward camera (`mon` + `mdirection 0`), or off (`moff`). SDK 2.0. */
    suspend fun setMissionPads(enabled: Boolean): CommandResult {
        if (!enabled) return request("moff", ACK_TIMEOUT_MS)
        val on = request("mon", ACK_TIMEOUT_MS)
        return if (on == CommandResult.Ok) request("mdirection 0", ACK_TIMEOUT_MS) else on
    }

    fun emergency() = send("emergency")

    fun close() {
        _connection.value = TelloConnectionState.DISCONNECTED
        scope.cancel()
        val command = commandSocket
        val state = stateSocket
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { command?.let { sendOn(it, TelloCommands.rc(0, 0, 0, 0)) } }
            command?.close()
            state?.close()
        }
    }

    /**
     * Sends [command] and waits for its answer. With [preempt], a busy ack channel does not delay the
     * command: it is sent at once and reported as [CommandResult.Unconfirmed].
     */
    private suspend fun request(command: String, timeoutMs: Long, preempt: Boolean = false): CommandResult =
        withContext(Dispatchers.IO) {
            if (!ackLock.tryLock()) {
                if (preempt) {
                    sendRaw(command)
                    return@withContext CommandResult.Unconfirmed
                }
                ackLock.lock()
            }
            try {
                while (responses.tryReceive().isSuccess) Unit
                sendRaw(command)
                withTimeoutOrNull(timeoutMs) { responses.receive() }
                    ?.let(TelloCommands::parseResponse)
                    ?: CommandResult.Timeout
            } finally {
                ackLock.unlock()
            }
        }

    private fun send(command: String) {
        scope.launch { runCatching { sendRaw(command) } }
    }

    private fun sendRaw(command: String) {
        commandSocket?.let { sendOn(it, command) }
    }

    private fun sendOn(socket: DatagramSocket, command: String) {
        val data = command.toByteArray()
        socket.send(DatagramPacket(data, data.size, host, COMMAND_PORT))
    }

    private fun openSockets() {
        val binder = socketBinder
        if (commandSocket?.isClosed != false) {
            val socket = DatagramSocket()
            binder?.invoke(socket)
            commandSocket = socket
            scope.launch { readResponses(socket) }
        }
        if (stateSocket?.isClosed != false) {
            // A bind failure is not fatal here: missing telemetry surfaces as LINK_LOST.
            runCatching { DatagramSocket(STATE_PORT).also { binder?.invoke(it) } }.getOrNull()?.let { socket ->
                stateSocket = socket
                scope.launch { listenState(socket) }
            }
        }
    }

    private fun closeSockets() {
        commandSocket?.close()
        commandSocket = null
        stateSocket?.close()
        stateSocket = null
    }

    private fun readResponses(socket: DatagramSocket) {
        runCatching {
            val buffer = ByteArray(1024)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length).trim()
                _lastResponse.value = text
                responses.trySend(text)
            }
        }
    }

    private fun listenState(socket: DatagramSocket) {
        runCatching {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                stateMonitor.onPacket()
                _telemetry.value = TelloTelemetry.parse(String(packet.data, 0, packet.length))
            }
        }
    }

    private suspend fun monitorLink() {
        while (scope.isActive) {
            val quality = stateMonitor.quality()
            _stateLink.value = quality
            when (_connection.value) {
                TelloConnectionState.CONNECTED ->
                    if (quality.level == LinkLevel.LOST) _connection.value = TelloConnectionState.LINK_LOST
                TelloConnectionState.LINK_LOST ->
                    if (quality.level == LinkLevel.GOOD) _connection.value = TelloConnectionState.CONNECTED
                else -> Unit
            }
            delay(MONITOR_PERIOD_MS)
        }
    }
}
