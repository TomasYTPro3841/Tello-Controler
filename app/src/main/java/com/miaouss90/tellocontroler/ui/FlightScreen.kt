package com.miaouss90.tellocontroler.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miaouss90.tellocontroler.FlightViewModel
import com.miaouss90.tellocontroler.controller.RcInput
import com.miaouss90.tellocontroler.flight.FlightState
import com.miaouss90.tellocontroler.flight.FlightStateMachine
import com.miaouss90.tellocontroler.tello.LinkLevel
import com.miaouss90.tellocontroler.tello.LinkQuality
import com.miaouss90.tellocontroler.tello.TelloConnectionState
import com.miaouss90.tellocontroler.tello.TelloH264Decoder
import com.miaouss90.tellocontroler.tello.TelloTelemetry
import com.miaouss90.tellocontroler.tello.TelloWifiState
import com.miaouss90.tellocontroler.ui.components.Banner
import com.miaouss90.tellocontroler.ui.components.Metric
import com.miaouss90.tellocontroler.ui.components.StatusPill
import com.miaouss90.tellocontroler.ui.components.StickIndicator
import com.miaouss90.tellocontroler.ui.components.TouchStick
import com.miaouss90.tellocontroler.ui.components.color
import com.miaouss90.tellocontroler.ui.hud.ArtificialHorizon
import com.miaouss90.tellocontroler.ui.hud.HeadingTape
import com.miaouss90.tellocontroler.ui.hud.HudMath
import com.miaouss90.tellocontroler.ui.hud.Reticle
import com.miaouss90.tellocontroler.ui.theme.HudColors
import kotlinx.coroutines.delay
import com.miaouss90.tellocontroler.ui.theme.TelloTheme
import com.miaouss90.tellocontroler.vision.VisionFrameGrabber

@Composable
fun FlightScreen(vm: FlightViewModel) {
    val telemetry by vm.telemetry.collectAsState()
    val connection by vm.connection.collectAsState()
    val wifiState by vm.wifiState.collectAsState()
    val stateLink by vm.stateLink.collectAsState()
    val videoLink by vm.videoLink.collectAsState()
    val lastResponse by vm.lastResponse.collectAsState()
    val controllerConnected by vm.controllerConnected.collectAsState()
    val emergencyArming by vm.emergencyArming.collectAsState()
    val flightState by vm.flightState.collectAsState()
    val takeoffBlock by vm.takeoffBlock.collectAsState()
    val settings by vm.settings.collectAsState()
    val notice by vm.notice.collectAsState()
    val rcOutput by vm.rcOutput.collectAsState()
    val flightStartedAt by vm.flightStartedAt.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val flightTime = flightStartedAt?.let { HudMath.flightTime(now - it) } ?: "--:--"
    val recordingSince by vm.recordingSince.collectAsState()
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.photoRequests.collect { PhotoCapture.capture(surfaceView) { vm.onPhotoCaptured(it) } }
    }
    val connected = connection == TelloConnectionState.CONNECTED
    val videoActive = videoLink.level == LinkLevel.GOOD || videoLink.level == LinkLevel.DEGRADED
    // Fallback: without a controller the touch sticks appear by themselves.
    val showTouchSticks = settings.touchSticks || !controllerConnected

    TelloTheme {
        Box(Modifier.fillMaxSize().background(HudColors.Night)) {
            // Keep the Tello 4:3 aspect ratio: letterbox on wide phones instead of stretching.
            VideoSurface(
                onSurfaceReady = { vm.startVideo(it) },
                onSurfaceDestroyed = { vm.stopVideo() },
                onViewCreated = { surfaceView = it },
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(TelloH264Decoder.WIDTH.toFloat() / TelloH264Decoder.HEIGHT, matchHeightConstraintsFirst = true),
            )

            val target by vm.target.collectAsState()
            val visionActive by vm.visionActive.collectAsState()
            VisionFrameGrabber(
                surfaceView,
                active = (visionActive || settings.qrActions) && videoActive,
                onFrame = { vm.onVisionFrame(it) },
                hiRes = settings.qrActions,
            )
            if (videoActive) {
                TargetLayer(
                    target = target,
                    onSelect = { x, y -> vm.selectTarget(x, y) },
                    onClear = { vm.clearTarget() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .aspectRatio(TelloH264Decoder.WIDTH.toFloat() / TelloH264Decoder.HEIGHT, matchHeightConstraintsFirst = true),
                )
            }

            if (connected) {
                if (settings.hudHorizon) {
                    ArtificialHorizon(telemetry.pitch, telemetry.roll, Modifier.fillMaxSize())
                }
                if (settings.hudReticle) Reticle(Modifier.align(Alignment.Center))
                if (settings.hudHeading) {
                    HeadingTape(telemetry.yaw, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
                }
            }

            if (!videoActive && !connected) {
                SetupChecklist(
                    wifiState = wifiState,
                    connection = connection,
                    controllerConnected = controllerConnected,
                    touchSticks = settings.touchSticks,
                    videoActive = videoActive,
                    onConnect = { vm.connect() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            TopBar(wifiState, connection, stateLink, videoLink, controllerConnected, telemetry, settings.minTakeoffBatteryPercent, flightTime)

            if (!videoActive && connected) {
                Text(
                    "WAITING FOR VIDEO…",
                    Modifier.align(Alignment.Center),
                    color = HudColors.Muted,
                    fontSize = 12.sp,
                )
            }

            if (showTouchSticks) {
                // With a controller the touch sticks are dimmed; dragging one is a deliberate takeover.
                TouchStick(
                    onChange = { x, y, active -> vm.touchLeft(x, y, active) },
                    dimmed = controllerConnected,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                )
                TouchStick(
                    onChange = { x, y, active -> vm.touchRight(x, y, active) },
                    dimmed = controllerConnected,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                )
            }

            FlightData(
                telemetry = telemetry,
                flightState = flightState,
                rate = settings.rate.name,
                mode = settings.mode.name,
                stateLink = stateLink,
                videoLink = videoLink,
                lastResponse = lastResponse,
                rcOutput = rcOutput.takeUnless { showTouchSticks },
                missionPadsEnabled = settings.missionPads,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )

            Row(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { settingsOpen = true }) { Text("SETTINGS") }
                if (!connected && connection != TelloConnectionState.CONNECTING) {
                    Button(onClick = { vm.connect() }) {
                        Text(if (connection == TelloConnectionState.LINK_LOST) "RECONNECT" else "CONNECT")
                    }
                }
                Button(
                    enabled = takeoffBlock == null,
                    onClick = { vm.takeoff() },
                    colors = ButtonDefaults.buttonColors(containerColor = HudColors.Green, contentColor = HudColors.Night),
                ) {
                    Text(
                        if (flightState == FlightState.TAKING_OFF) "TAKING OFF…" else "A  TAKE OFF",
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Escape hatch: state says airborne but the drone is at ground level (e.g. caught by hand).
                if (flightState != FlightState.LANDED && connected &&
                    telemetry.heightCm < FlightStateMachine.AIRBORNE_HEIGHT_CM
                ) {
                    TextButton(onClick = { vm.markLanded() }) { Text("MARK LANDED") }
                }
                // SAFETY: LAND stays enabled whenever a command link may exist; it is never guarded.
                OutlinedButton(enabled = connection != TelloConnectionState.DISCONNECTED, onClick = { vm.land() }) {
                    Text(if (flightState == FlightState.LANDING) "LANDING…" else "B  LAND")
                }
            }

            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val hovering by vm.hoverEngaged.collectAsState()
                if (flightState == FlightState.FLYING) {
                    Button(
                        onClick = { vm.toggleHover() },
                        colors = if (hovering) {
                            ButtonDefaults.buttonColors(containerColor = HudColors.Green, contentColor = HudColors.Night)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text(if (hovering) "L1  HOVERING" else "L1  HOVER", fontWeight = FontWeight.Bold) }
                }
                if (visionActive) {
                    val following by vm.followEngaged.collectAsState()
                    Button(
                        onClick = { vm.toggleFollow() },
                        colors = if (following) {
                            ButtonDefaults.buttonColors(containerColor = HudColors.Cyan, contentColor = HudColors.Night)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text(if (following) "RB  FOLLOWING" else "RB  FOLLOW", fontWeight = FontWeight.Bold) }
                    val orbiting by vm.orbitEngaged.collectAsState()
                    OutlinedButton(onClick = { vm.toggleOrbit() }) { Text(if (orbiting) "ORBITING" else "ORBIT") }
                    OutlinedButton(onClick = { vm.clearTarget() }) { Text("✕ TARGET") }
                }
                OutlinedButton(enabled = videoActive, onClick = { vm.requestPhoto() }) { Text("X  PHOTO") }
                val recording = recordingSince
                OutlinedButton(onClick = { vm.toggleRecording() }) {
                    Text(
                        if (recording != null) "■ REC ${HudMath.flightTime(now - recording)}" else "●  REC",
                        color = if (recording != null) HudColors.Red else Color.Unspecified,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 116.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (wifiState == TelloWifiState.LOST) {
                    Banner("TELLO WI-FI LOST — sticks neutralized, reconnecting when it returns", HudColors.Red)
                } else if (connection == TelloConnectionState.LINK_LOST) {
                    Banner("TELLO LINK LOST — no telemetry for ${(stateLink.lastPacketAgeMs ?: 0) / 1000} s", HudColors.Red)
                }
                if (emergencyArming) Banner("HOLD MENU — EMERGENCY MOTOR STOP", HudColors.Red)
                notice?.let { Banner(it, HudColors.Panel) }
            }

            if (settingsOpen) {
                SettingsScreen(
                    updateAllowed = connection == TelloConnectionState.DISCONNECTED || connection == TelloConnectionState.ERROR,
                    settings = settings,
                    onSettingsChange = { vm.updateSettings(it) },
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    wifiState: TelloWifiState,
    connection: TelloConnectionState,
    stateLink: LinkQuality,
    videoLink: LinkQuality,
    controllerConnected: Boolean,
    telemetry: TelloTelemetry,
    minBattery: Int,
    flightTime: String,
) {
    val tello = when (connection) {
        TelloConnectionState.CONNECTED, TelloConnectionState.LINK_LOST -> stateLink.level.color()
        TelloConnectionState.ERROR -> HudColors.Red
        TelloConnectionState.CONNECTING -> HudColors.Amber
        TelloConnectionState.DISCONNECTED -> HudColors.Muted
    }
    val battery = telemetry.batteryPercent
    val batteryColor = when {
        connection != TelloConnectionState.CONNECTED -> Color.White
        battery < minBattery -> HudColors.Red
        battery < minBattery + 10 -> HudColors.Amber
        else -> Color.White
    }
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                "WIFI",
                when (wifiState) {
                    TelloWifiState.LOCKED -> HudColors.Green
                    TelloWifiState.SEARCHING -> HudColors.Amber
                    TelloWifiState.LOST, TelloWifiState.UNAVAILABLE -> HudColors.Red
                    TelloWifiState.IDLE -> HudColors.Muted
                },
            )
            StatusPill("TELLO", tello)
            StatusPill("VIDEO", videoLink.level.color())
            StatusPill("XBOX", if (controllerConnected) HudColors.Green else HudColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("BAT", "$battery%", batteryColor)
            Metric("ALT", "${telemetry.heightCm} cm")
            Metric("SPD", "${HudMath.oneDecimal(HudMath.horizontalSpeedMs(telemetry.speedX, telemetry.speedY))} m/s")
            Metric("V/S", "${HudMath.oneDecimal(HudMath.verticalSpeedMs(telemetry.speedZ))} m/s")
            Metric("TIME", flightTime)
        }
    }
}

@Composable
private fun FlightData(
    telemetry: TelloTelemetry,
    flightState: FlightState,
    rate: String,
    mode: String,
    stateLink: LinkQuality,
    videoLink: LinkQuality,
    lastResponse: String,
    rcOutput: RcInput?,
    missionPadsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Mode 2: left = yaw (x) / throttle (y), right = roll (x) / pitch (y); what is actually sent.
        if (rcOutput != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StickIndicator("YAW / THR", x = rcOutput.yaw, y = rcOutput.throttle)
                StickIndicator("ROLL / PITCH", x = rcOutput.roll, y = rcOutput.pitch)
            }
        }
        Text(
            "$flightState  •  $mode  •  RATE $rate  •  MOTOR ${telemetry.flightTimeSeconds} s",
            color = HudColors.Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "YAW ${telemetry.yaw}°   •   PITCH ${telemetry.pitch}°   •   ROLL ${telemetry.roll}°   •   TOF ${telemetry.tofCm} cm",
            color = Color.White,
            fontSize = 12.sp,
        )
        if (missionPadsEnabled) {
            val pad = telemetry.missionPad
            Text(
                pad?.let { "PAD #${it.id}   x ${it.xCm}   y ${it.yCm}   z ${it.zCm} cm" } ?: "PAD —  (no Mission Pad in view)",
                color = if (pad != null) HudColors.Green else HudColors.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "LINK ${stateLink.describe()}   VIDEO ${videoLink.describe()}   LAST ${lastResponse.ifEmpty { "—" }}",
            color = HudColors.Muted,
            fontSize = 9.sp,
        )
    }
}

private fun LinkQuality.describe(): String =
    lastPacketAgeMs?.let { "$packetsPerSecond/s · ${it} ms" } ?: "—"
