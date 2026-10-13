package com.miaouss90.tellocontroler

import android.app.Application
import android.graphics.Bitmap
import android.net.Network
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miaouss90.tellocontroler.controller.AlertInputs
import com.miaouss90.tellocontroler.controller.AlertMonitor
import com.miaouss90.tellocontroler.controller.ControllerRumble
import com.miaouss90.tellocontroler.controller.FlightAlert
import com.miaouss90.tellocontroler.controller.RcInput
import com.miaouss90.tellocontroler.controller.RcSafetyLoop
import com.miaouss90.tellocontroler.controller.RcShaper
import com.miaouss90.tellocontroler.controller.StickAxes
import com.miaouss90.tellocontroler.controller.StickMapper
import com.miaouss90.tellocontroler.flight.FlightEvent
import com.miaouss90.tellocontroler.flight.FlightState
import com.miaouss90.tellocontroler.flight.FlightStateMachine
import com.miaouss90.tellocontroler.flight.MotorStopDetector
import com.miaouss90.tellocontroler.flight.TakeoffBlock
import com.miaouss90.tellocontroler.flight.TakeoffGuard
import com.miaouss90.tellocontroler.record.FlightRecorder
import com.miaouss90.tellocontroler.record.MediaStorage
import com.miaouss90.tellocontroler.record.VideoRecorder
import com.miaouss90.tellocontroler.settings.FlightProfiles
import com.miaouss90.tellocontroler.settings.FlightSettings
import com.miaouss90.tellocontroler.settings.SettingsRepository
import com.miaouss90.tellocontroler.tello.CommandResult
import com.miaouss90.tellocontroler.tello.LinkLevel
import com.miaouss90.tellocontroler.tello.LinkMonitor
import com.miaouss90.tellocontroler.tello.LinkQuality
import com.miaouss90.tellocontroler.tello.NalSplitter
import com.miaouss90.tellocontroler.tello.TelloClient
import com.miaouss90.tellocontroler.tello.TelloConnectionState
import com.miaouss90.tellocontroler.tello.TelloH264Decoder
import com.miaouss90.tellocontroler.tello.TelloTelemetry
import com.miaouss90.tellocontroler.tello.TelloVideoReceiver
import com.miaouss90.tellocontroler.tello.TelloWifiManager
import com.miaouss90.tellocontroler.tello.TelloWifiState
import com.miaouss90.tellocontroler.vision.ColorTracker
import com.miaouss90.tellocontroler.vision.FaceTracker
import com.miaouss90.tellocontroler.vision.FollowController
import com.miaouss90.tellocontroler.vision.GrayFrame
import com.miaouss90.tellocontroler.vision.InputArbiter
import com.miaouss90.tellocontroler.vision.OrbitController
import com.miaouss90.tellocontroler.vision.QrAction
import com.miaouss90.tellocontroler.vision.QrCommand
import com.miaouss90.tellocontroler.vision.QrDebouncer
import com.miaouss90.tellocontroler.vision.QrReader
import com.miaouss90.tellocontroler.vision.TargetTracker
import com.miaouss90.tellocontroler.vision.TemplateTracker
import com.miaouss90.tellocontroler.vision.TrackResult
import com.miaouss90.tellocontroler.vision.TrackerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Orchestrates transport, safety loop, flight state, video and settings. Holds no Android UI references. */
class FlightViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Emergency cuts the motors mid-air: it must be a deliberate, sustained press. */
        const val EMERGENCY_HOLD_MS = 1000L
        private const val NOTICE_MS = 4000L
        private const val MONITOR_PERIOD_MS = 250L
        private const val FLIGHT_LOG_PERIOD_MS = 100L
        /** Follow ignores tracking results older than this (frames stopped, app paused…). */
        private const val TARGET_FRESH_MS = 500L
        /** Follow disengages after the target has been lost or stale this long. */
        private const val FOLLOW_GIVE_UP_MS = 2000L
        private const val QR_PERIOD_MS = 400L
    }

    private val client = TelloClient()
    private val wifi = TelloWifiManager(app)
    private val settingsRepository = SettingsRepository(app)
    private val videoMonitor = LinkMonitor()
    private val motorStopDetector = MotorStopDetector()
    private val nalSplitter = NalSplitter()
    private val storage = MediaStorage(app)
    private val flightRecorder = FlightRecorder(File(app.cacheDir, "flights"))
    @Volatile private var videoRecorder: VideoRecorder? = null
    private var videoReceiver: TelloVideoReceiver? = null
    private var decoder: TelloH264Decoder? = null
    private var emergencyJob: Job? = null
    private var noticeJob: Job? = null
    private var videoSurface: Surface? = null
    private var socketBinder: ((DatagramSocket) -> Unit)? = null

    /** The user asked to fly: keep reconnecting the SDK whenever the Tello Wi-Fi comes back. */
    @Volatile private var wantConnected = false

    // Input sources. Android only reports stick *changes*, so the controller position is held and
    // re-sent by the input pump while the controller is present and the app is in the foreground.
    @Volatile private var controllerAxes = StickAxes.NEUTRAL
    @Volatile private var touchAxes = StickAxes.NEUTRAL
    @Volatile private var touchLeftActive = false
    @Volatile private var touchRightActive = false
    @Volatile private var foreground = true

    val telemetry: StateFlow<TelloTelemetry> = client.telemetry
    val connection: StateFlow<TelloConnectionState> = client.connection
    val lastResponse: StateFlow<String> = client.lastResponse
    val stateLink: StateFlow<LinkQuality> = client.stateLink
    val settings: StateFlow<FlightSettings> = settingsRepository.settings
    val wifiState: StateFlow<TelloWifiState> = wifi.state

    private val _videoLink = MutableStateFlow(LinkQuality.NONE)
    val videoLink = _videoLink.asStateFlow()

    /** The RC command actually sent to the aircraft (after staleness checks), for the HUD stick indicators. */
    private val _rcOutput = MutableStateFlow(RcInput.NEUTRAL)
    val rcOutput = _rcOutput.asStateFlow()

    private val _flightState = MutableStateFlow(FlightState.LANDED)
    val flightState = _flightState.asStateFlow()

    /** Wall-clock time the motors started for the current flight, null on the ground (HUD flight timer). */
    private val _flightStartedAt = MutableStateFlow<Long?>(null)
    val flightStartedAt = _flightStartedAt.asStateFlow()

    private val _controllerConnected = MutableStateFlow(false)
    val controllerConnected = _controllerConnected.asStateFlow()

    private val _emergencyArming = MutableStateFlow(false)
    val emergencyArming = _emergencyArming.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    /** Wall-clock start of the current video recording, null when not recording. */
    private val _recordingSince = MutableStateFlow<Long?>(null)
    val recordingSince = _recordingSince.asStateFlow()

    // Target tracking (vision only for now: it does not steer the aircraft).
    private var tracker: TargetTracker = TemplateTracker()
    private val qrDebouncer = QrDebouncer()
    private var lastQrAt = 0L
    private val pendingSelection = AtomicReference<Pair<Float, Float>?>(null)
    @Volatile private var clearTracker = false

    /** True while the UI should grab frames for the tracker (a target is selected or being selected). */
    private val _visionActive = MutableStateFlow(false)
    val visionActive = _visionActive.asStateFlow()

    private val _target = MutableStateFlow<TrackResult?>(null)
    val target = _target.asStateFlow()
    @Volatile private var targetAt = 0L
    @Volatile private var targetSeenAt = 0L

    /** FOLLOW: the aircraft turns/climbs to keep the target centered while the pilot does not touch the sticks. */
    private val _followEngaged = MutableStateFlow(false)
    val followEngaged = _followEngaged.asStateFlow()

    /** ORBIT is FOLLOW plus a constant sideways roll around the target (so [followEngaged] is true too). */
    private val _orbitEngaged = MutableStateFlow(false)
    val orbitEngaged = _orbitEngaged.asStateFlow()

    /** HOVER: neutral RC is held (the Tello stabilizes itself) and FOLLOW is off; any pilot stick input ends it. */
    private val _hoverEngaged = MutableStateFlow(false)
    val hoverEngaged = _hoverEngaged.asStateFlow()

    /** The UI owns the SurfaceView, so it performs the frame grab when asked. */
    private val _photoRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoRequests: SharedFlow<Unit> = _photoRequests

    val takeoffBlock: StateFlow<TakeoffBlock?> =
        combine(connection, stateLink, telemetry, settings, flightState) { _, _, _, _, _ -> currentTakeoffBlock() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TakeoffBlock.NOT_CONNECTED)

    private val rcShaper = RcShaper()
    private var wasHeightLimited = false

    private val rcLoop = RcSafetyLoop(
        send = {
            client.rc(it)
            _rcOutput.value = it
            if (rcShaper.heightLimited && !wasHeightLimited) {
                showNotice("Height limit ${FlightProfiles.resolve(settings.value).maxHeightCm} cm reached")
            }
            wasHeightLimited = rcShaper.heightLimited
        },
        shape = {
            val profile = FlightProfiles.resolve(settings.value)
            rcShaper.shape(it, profile.smoothing, profile.maxHeightCm, telemetry.value.heightCm)
        },
        onNeutralized = { rcShaper.reset() },
    ).also { it.start() }

    init {
        viewModelScope.launch { telemetry.collect { reduce(FlightEvent.Height(it.heightCm)) } }
        viewModelScope.launch { wifi.network.collect { onTelloNetwork(it) } }
        viewModelScope.launch {
            // Applied on every (re)connection and whenever the setting changes.
            combine(connection, settings) { c, s -> (c == TelloConnectionState.CONNECTED) to s.missionPads }
                .distinctUntilChanged()
                .collect { (connected, enabled) ->
                    if (!connected) return@collect
                    val result = client.setMissionPads(enabled)
                    if (enabled && result != CommandResult.Ok) {
                        showNotice("Mission Pads unavailable: ${describe(result)} (Tello EDU / SDK 2.0 only)")
                    }
                }
        }
        viewModelScope.launch {
            var previous: AlertInputs? = null
            combine(connection, wifi.state, telemetry, flightState, emergencyArming) { c, w, t, f, arming ->
                AlertInputs(
                    linkLost = c == TelloConnectionState.LINK_LOST || w == TelloWifiState.LOST,
                    airborne = f != FlightState.LANDED,
                    batteryPercent = t.batteryPercent,
                    lowBatteryPercent = settings.value.minTakeoffBatteryPercent,
                    emergencyArming = arming,
                )
            }.collect { current ->
                AlertMonitor.alerts(previous, current).forEach { onAlert(it) }
                previous = current
            }
        }
        viewModelScope.launch {
            wifi.state.collect {
                when (it) {
                    TelloWifiState.UNAVAILABLE -> {
                        wantConnected = false
                        showNotice("Tello Wi-Fi not found or not approved")
                    }
                    // Re-arm at once: the new request waits for the Tello access point to come back.
                    TelloWifiState.LOST -> if (wantConnected) wifi.request()
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _videoLink.value = videoMonitor.quality()
                checkMotorsStopped()
                superviseFollow()
                superviseHover()
                delay(MONITOR_PERIOD_MS)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                pumpInput()
                delay(RcSafetyLoop.PERIOD_MS)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                recordFlightSample()
                delay(FLIGHT_LOG_PERIOD_MS)
            }
        }
    }

    /** Locks the Tello Wi-Fi first; the SDK handshake starts once the network is available. */
    fun connect() {
        wantConnected = true
        if (wifi.state.value == TelloWifiState.LOCKED) client.connect() else wifi.request()
    }

    fun startVideo(surface: Surface) {
        stopVideo()
        videoSurface = surface
        videoMonitor.reset()
        nalSplitter.reset()
        decoder = TelloH264Decoder(surface).also { it.start() }
        startVideoReceiver()
    }

    fun stopVideo() {
        videoSurface = null
        videoReceiver?.stop()
        videoReceiver = null
        decoder?.stop()
        decoder = null
    }

    private fun startVideoReceiver() {
        videoReceiver?.stop()
        videoReceiver = TelloVideoReceiver(socketBinder) { chunk ->
            videoMonitor.onPacket()
            val endOfFrame = chunk.size < TelloVideoReceiver.FULL_PACKET_BYTES
            nalSplitter.push(chunk, endOfFrame).forEach { nal ->
                decoder?.offerNal(nal)
                videoRecorder?.onNal(nal)
            }
            if (endOfFrame) {
                decoder?.endOfFrame()
                videoRecorder?.endOfFrame()
            }
        }.also { it.start() }
    }

    private fun onTelloNetwork(network: Network?) {
        if (network == null) {
            if (socketBinder == null) return
            // SAFETY: the aircraft is unreachable; drop held input so nothing resumes on reconnection.
            socketBinder = null
            controllerAxes = StickAxes.NEUTRAL
            neutralControls()
            client.onNetworkLost()
            if (wantConnected) showNotice("Tello Wi-Fi lost, waiting for it to come back…")
            return
        }
        val binder: (DatagramSocket) -> Unit = { network.bindSocket(it) }
        socketBinder = binder
        client.useNetwork(binder)
        if (videoSurface != null) startVideoReceiver()
        if (wantConnected) client.connect()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            controllerAxes = StickAxes.NEUTRAL
            touchAxes = StickAxes.NEUTRAL
            touchLeftActive = false
            touchRightActive = false
            neutralControls()
            emergencyReleased()
        }
    }

    fun setControllerConnected(connected: Boolean) {
        _controllerConnected.value = connected
        if (!connected) {
            controllerAxes = StickAxes.NEUTRAL
            neutralControls()
            emergencyReleased()
        }
    }

    fun controllerInput(axes: StickAxes) {
        _controllerConnected.value = true
        controllerAxes = axes
        pumpInput()
    }

    fun touchLeft(x: Float, y: Float, active: Boolean) {
        announceTouchTakeover(active)
        touchLeftActive = active
        touchAxes = touchAxes.copy(leftX = if (active) x else 0f, leftY = if (active) y else 0f)
        pumpInput()
    }

    fun touchRight(x: Float, y: Float, active: Boolean) {
        announceTouchTakeover(active)
        touchRightActive = active
        touchAxes = touchAxes.copy(rightX = if (active) x else 0f, rightY = if (active) y else 0f)
        pumpInput()
    }

    /** With a controller connected, touching a (dimmed) touch stick is a deliberate takeover: say so. */
    private fun announceTouchTakeover(active: Boolean) {
        val wasTouching = touchLeftActive || touchRightActive
        if (active && !wasTouching && _controllerConnected.value) {
            showNotice("Touch sticks took control — release to give it back to the controller")
        }
    }

    fun neutralControls() {
        rcLoop.neutral()
        disengageHover(null)
    }

    fun updateSettings(settings: FlightSettings) = settingsRepository.update { settings }

    fun cycleRate() {
        settingsRepository.update { it.copy(rate = it.rate.next()) }
        showNotice("Rate: ${settings.value.rate.name}")
    }

    fun takeoff() {
        currentTakeoffBlock()?.let {
            showNotice(describe(it))
            return
        }
        reduce(FlightEvent.TakeoffSent)
        viewModelScope.launch {
            val result = client.takeoff()
            val airborne = result == CommandResult.Ok ||
                telemetry.value.heightCm >= FlightStateMachine.AIRBORNE_HEIGHT_CM
            reduce(FlightEvent.TakeoffDone(airborne))
            if (result != CommandResult.Ok) showNotice("Takeoff: ${describe(result)}")
        }
    }

    /** Pilot says the aircraft is on the ground: re-enables takeoff when no automatic signal did. */
    fun markLanded() = reduce(FlightEvent.ManualLanded)

    /** SAFETY: landing is never blocked by flight state or guards. */
    fun land() {
        reduce(FlightEvent.LandSent)
        viewModelScope.launch {
            when (val result = client.land()) {
                CommandResult.Ok -> reduce(FlightEvent.LandDone(ok = true))
                is CommandResult.Error -> {
                    reduce(FlightEvent.LandDone(ok = false))
                    showNotice("Land: ${describe(result)}")
                }
                CommandResult.Timeout, CommandResult.Unconfirmed -> Unit
            }
        }
    }

    fun emergencyPressed() {
        if (emergencyJob?.isActive == true) return
        _emergencyArming.value = true
        emergencyJob = viewModelScope.launch {
            delay(EMERGENCY_HOLD_MS)
            client.emergency()
            reduce(FlightEvent.EmergencySent)
            _emergencyArming.value = false
            showNotice("EMERGENCY motor stop sent")
        }
    }

    fun emergencyReleased() {
        emergencyJob?.cancel()
        emergencyJob = null
        _emergencyArming.value = false
    }

    /** Tap on the video at normalized (x, y): track what is there from the next frame. */
    fun selectTarget(x: Float, y: Float) {
        pendingSelection.set(x to y)
        _visionActive.value = true
    }

    fun clearTarget() {
        disengageFollow(null)
        clearTracker = true
        pendingSelection.set(null)
        _visionActive.value = false
        _target.value = null
    }

    private fun newTracker(kind: TrackerKind): TargetTracker = when (kind) {
        TrackerKind.TEMPLATE -> TemplateTracker()
        TrackerKind.COLOR -> ColorTracker()
        TrackerKind.FACE -> FaceTracker()
    }

    /** Vision thread: one video frame (twice the tracker size while QR actions are on). */
    fun onVisionFrame(raw: GrayFrame) {
        if (settings.value.qrActions) readQr(raw)
        val frame = if (raw.width > GrayFrame.VISION_WIDTH) raw.downscale2() else raw
        if (clearTracker) {
            tracker.clear()
            clearTracker = false
        }
        pendingSelection.getAndSet(null)?.let { (x, y) ->
            tracker = newTracker(settings.value.tracker)
            if (!tracker.select(frame, x, y)) {
                showNotice("Nothing to track there — pick a textured object")
                if (!tracker.hasTarget) _visionActive.value = false
                return
            }
        }
        if (!tracker.hasTarget || !_visionActive.value) return
        val result = tracker.track(frame)
        if (_visionActive.value) {
            val now = System.currentTimeMillis()
            _target.value = result
            targetAt = now
            if (!result.lost) targetSeenAt = now
        }
    }

    /** Only land / hover / photo / record can be triggered: a QR code in the scene must never move the aircraft. */
    private fun readQr(frame: GrayFrame) {
        val now = System.currentTimeMillis()
        if (now - lastQrAt < QR_PERIOD_MS) return
        lastQrAt = now
        val action = QrCommand.parse(QrReader.decode(frame)) ?: return
        if (!qrDebouncer.accept(action)) return
        showNotice("QR: ${action.name}")
        when (action) {
            QrAction.LAND -> land()
            QrAction.HOVER -> if (!_hoverEngaged.value) toggleHover()
            QrAction.PHOTO -> requestPhoto()
            QrAction.RECORD -> toggleRecording()
        }
    }

    /** L1 / HUD HOVER. Only while flying. Sends the SDK `stop` once, then keeps feeding neutral RC. */
    fun toggleHover() {
        if (_hoverEngaged.value) return disengageHover("Hover off")
        if (flightState.value != FlightState.FLYING) return showNotice("Hover works in flight only")
        disengageFollow(null)
        _hoverEngaged.value = true
        showNotice("HOVER on — any stick input takes over")
        viewModelScope.launch(Dispatchers.IO) {
            recordFlightSample("hover:on")
            client.stop()
        }
    }

    private fun disengageHover(reason: String?) {
        if (!_hoverEngaged.value) return
        _hoverEngaged.value = false
        reason?.let { showNotice(it) }
        viewModelScope.launch(Dispatchers.IO) { recordFlightSample("hover:off") }
    }

    /** HUD ORBIT: like FOLLOW, but circling the target at the distance it had when selected. */
    fun toggleOrbit() = toggleFollow(orbit = true)

    /** RB / HUD FOLLOW. Only engages while flying with a locked target. */
    fun toggleFollow(orbit: Boolean = false) {
        if (_followEngaged.value) return disengageFollow(if (_orbitEngaged.value) "Orbit off" else "Follow off")
        val target = _target.value
        when {
            target == null || target.lost -> showNotice("Select a target first (tap the video)")
            flightState.value != FlightState.FLYING -> showNotice("Follow works in flight only")
            else -> {
                disengageHover(null)
                targetSeenAt = System.currentTimeMillis()
                _orbitEngaged.value = orbit
                _followEngaged.value = true
                showNotice("${if (orbit) "ORBIT" else "FOLLOW"} on — any stick input takes over")
                viewModelScope.launch(Dispatchers.IO) { recordFlightSample(if (orbit) "orbit:on" else "follow:on") }
            }
        }
    }

    private fun disengageFollow(reason: String?) {
        if (!_followEngaged.value) return
        _followEngaged.value = false
        _orbitEngaged.value = false
        reason?.let { showNotice(it) }
        viewModelScope.launch(Dispatchers.IO) { recordFlightSample("follow:off") }
    }

    /** The follow command for now, or null when follow must not act (off, stale or lost target). */
    private fun followCommand(): RcInput? {
        if (!_followEngaged.value) return null
        val target = _target.value ?: return null
        if (System.currentTimeMillis() - targetAt > TARGET_FRESH_MS) return null
        if (!_orbitEngaged.value) return FollowController.command(target)
        val s = settings.value
        return OrbitController.command(target, s.orbitClockwise, s.orbitSpeed)
    }

    /** SAFETY: hover ends when not flying or without a link (the next takeoff must never start "hovering"). */
    private fun superviseHover() {
        if (!_hoverEngaged.value) return
        if (flightState.value != FlightState.FLYING || connection.value != TelloConnectionState.CONNECTED) {
            disengageHover("Hover off")
        }
    }

    /** SAFETY: follow stops on landing, link loss or a target lost for [FOLLOW_GIVE_UP_MS]. */
    private fun superviseFollow() {
        if (!_followEngaged.value) return
        val reason = when {
            flightState.value != FlightState.FLYING -> "Follow off: not flying"
            connection.value != TelloConnectionState.CONNECTED -> "Follow off: link lost"
            System.currentTimeMillis() - targetSeenAt > FOLLOW_GIVE_UP_MS -> "Follow off: target lost"
            else -> null
        }
        if (reason != null) {
            disengageFollow(reason)
            if (settings.value.rumbleAlerts) ControllerRumble.play(FlightAlert.EMERGENCY_ARMING)
        }
    }

    fun requestPhoto() {
        if (videoLink.value.level != LinkLevel.GOOD) {
            showNotice("No video to photograph")
            return
        }
        _photoRequests.tryEmit(Unit)
    }

    fun onPhotoCaptured(bitmap: Bitmap?) {
        if (bitmap == null) {
            showNotice("Photo failed")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { storage.savePhoto(bitmap, "tello-${timestamp()}.jpg") }
                .onSuccess { showNotice("Photo saved: $it") }
                .onFailure { showNotice("Photo failed: ${it.message}") }
        }
    }

    fun toggleRecording() {
        if (videoRecorder != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        runCatching { VideoRecorder(storage.createVideo("tello-${timestamp()}.mp4")) }
            .onSuccess {
                videoRecorder = it
                _recordingSince.value = System.currentTimeMillis()
                showNotice("Recording (starts at the next key frame)")
            }
            .onFailure { showNotice("Cannot record: ${it.message}") }
    }

    private fun stopRecording() {
        val recorder = videoRecorder ?: return
        videoRecorder = null
        _recordingSince.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val saved = recorder.stop()
            showNotice(if (saved) "Video saved in Movies/${MediaStorage.FOLDER}" else "No video frames recorded")
        }
    }

    override fun onCleared() {
        videoRecorder?.stop()
        videoRecorder = null
        emergencyReleased()
        stopVideo()
        rcLoop.stop()
        client.close()
        wifi.release()
        super.onCleared()
    }

    private fun pumpInput() {
        if (!foreground) return
        val s = settings.value
        val axes = when {
            touchLeftActive || touchRightActive -> touchAxes
            _controllerConnected.value -> controllerAxes
            else -> null
        }
        val profile = FlightProfiles.resolve(s)
        val pilot = axes?.let { StickMapper.map(it, s.deadZone, profile.scale, profile.expo) }
        if (pilot != null && !pilot.isNeutral) disengageHover("Hover off: stick input")
        // Pilot sticks always win; follow only acts while they are centered. Nothing to send ⇒ watchdog neutral.
        val assist = if (_hoverEngaged.value) RcInput.NEUTRAL else followCommand()
        InputArbiter.choose(pilot, assist)?.let { rcLoop.update(it) }
    }

    /**
     * Polled, not driven by telemetry emissions: StateFlow drops identical packets, and a landed Tello sends
     * identical packets, so an emission-driven check would never see the counter stay frozen.
     */
    /** Flight recorder: one CSV per flight (motors on → landed), exported to Download/TelloControler. */
    private fun recordFlightSample(event: String? = null) {
        val state = flightState.value
        val now = System.currentTimeMillis()
        synchronized(flightRecorder) {
            if (state != FlightState.LANDED && settings.value.flightLogs && !flightRecorder.isRecording) {
                flightRecorder.start(now, "flight-${timestamp()}.csv")
            }
            flightRecorder.record(now, event, state, telemetry.value, rcOutput.value)
            if (state == FlightState.LANDED && flightRecorder.isRecording) {
                flightRecorder.stop()?.let(::exportFlightLog)
            }
        }
    }

    private fun exportFlightLog(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { storage.saveFile(file, file.name, "text/csv") }
                .onSuccess { showNotice("Flight log saved: $it") }
            file.delete()
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    private fun onAlert(alert: FlightAlert) {
        viewModelScope.launch(Dispatchers.IO) { recordFlightSample("alert:${alert.name}") }
        if (settings.value.rumbleAlerts) ControllerRumble.play(alert)
        when (alert) {
            FlightAlert.BATTERY_LOW -> showNotice("Battery low: ${telemetry.value.batteryPercent}% — land soon")
            FlightAlert.BATTERY_CRITICAL -> showNotice("Battery critical: ${telemetry.value.batteryPercent}% — LAND NOW")
            FlightAlert.LINK_LOST, FlightAlert.EMERGENCY_ARMING -> Unit
        }
    }

    private fun checkMotorsStopped() {
        if (stateLink.value.level != LinkLevel.GOOD) return
        val now = System.currentTimeMillis()
        val stopped = motorStopDetector.onTelemetry(telemetry.value.flightTimeSeconds, now)
        if (!stopped && flightState.value == FlightState.LANDED && motorStopDetector.motorsRunning(now)) {
            reduce(FlightEvent.MotorsRunning)
        }
        if (stopped) {
            motorStopDetector.reset()
            if (flightState.value != FlightState.LANDED) {
                reduce(FlightEvent.MotorsStopped)
                showNotice("Landing detected (motors stopped)")
            }
        }
    }

    private fun currentTakeoffBlock(): TakeoffBlock? {
        val link = stateLink.value.level
        return TakeoffGuard.check(
            connected = connection.value == TelloConnectionState.CONNECTED,
            telemetryFresh = link == LinkLevel.GOOD || link == LinkLevel.DEGRADED,
            batteryPercent = telemetry.value.batteryPercent,
            minBatteryPercent = settings.value.minTakeoffBatteryPercent,
            state = flightState.value,
        )
    }

    private fun describe(block: TakeoffBlock) = when (block) {
        TakeoffBlock.NOT_CONNECTED -> "Connect to the Tello first"
        TakeoffBlock.NO_TELEMETRY -> "No telemetry from the Tello"
        TakeoffBlock.NOT_LANDED -> "Already airborne"
        TakeoffBlock.LOW_BATTERY ->
            "Battery ${telemetry.value.batteryPercent}% below takeoff minimum ${settings.value.minTakeoffBatteryPercent}%"
    }

    private fun describe(result: CommandResult) = when (result) {
        CommandResult.Ok -> "ok"
        is CommandResult.Error -> result.message
        CommandResult.Timeout -> "no answer from the Tello"
        CommandResult.Unconfirmed -> "sent, not confirmed"
    }

    private fun reduce(event: FlightEvent) {
        val before = _flightState.value
        val state = _flightState.updateAndGet { FlightStateMachine.reduce(it, event) }
        if (state == FlightState.LANDED && before != FlightState.LANDED) motorStopDetector.reset()
        if (state != before) viewModelScope.launch(Dispatchers.IO) { recordFlightSample("state:$state") }
        when {
            state == FlightState.LANDED -> _flightStartedAt.value = null
            _flightStartedAt.value == null -> _flightStartedAt.value = System.currentTimeMillis()
        }
    }

    @Synchronized
    private fun showNotice(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }
}
