package com.jrs8205.appletvremote.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.protocol.companion.CompanionException
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.TouchPhase
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import com.jrs8205.appletvremote.remote.WakeProgress
import com.jrs8205.appletvremote.remote.WakeStage
import com.jrs8205.appletvremote.ui.keyboard.TextInputSheet
import com.jrs8205.appletvremote.ui.theme.RemoteColors
import kotlinx.coroutines.delay

/** The Siri Remote layout, anchored to the bottom of the screen so every control sits under the thumb. */
@Composable
fun RemoteScreen(viewModel: RemoteViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showKeyboard by remember { mutableStateOf(false) }
    // The TV reports when a text field gains focus; open the sheet then, and close it when focus leaves.
    LaunchedEffect(state.keyboard != null) { showKeyboard = state.keyboard != null }
    LaunchedEffect(state.device?.credentials?.controller?.pairingId) { if (state.device != null) viewModel.connect() }

    val padActions = remember(viewModel) {
        object : ClickPadActions {
            override fun click(button: HidButton) = viewModel.press(button)
            override fun hold(button: HidButton) = viewModel.hold(button, BUTTON_HOLD_MS)
            override fun touch(phase: TouchPhase, x: Int, y: Int) = viewModel.touch(phase, x, y)
        }
    }
    val haptics = settings.hapticsEnabled
    val bodyColor = if (isSystemInDarkTheme()) RemoteColors.AluminiumDark else RemoteColors.AluminiumLight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, top = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.device?.name ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        state.lgTvPrompt -> stringResource(R.string.settings_lg_prompted)
                        state.wakingTv -> wakeLabel(state.wakeProgress)
                        else -> connectionLabel(state.connection)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val failure = state.connection as? ConnectionState.Failed
                if (failure != null && !state.wakingTv) {
                    Text(failureLabel(failure.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = { showKeyboard = true }) { Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.cd_keyboard)) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings)) }
        }
        // Sits at the bottom when there is room and scrolls when the window is shorter than the remote (landscape, split screen).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .background(bodyColor, RoundedCornerShape(36.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        RemoteButton(
                            contentDescription = stringResource(R.string.cd_power),
                            onTap = viewModel::togglePower,
                            size = 44.dp,
                            haptics = haptics,
                        ) { ButtonIcon(Icons.Default.PowerSettingsNew, size = 22.dp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    ClickPad(
                        mode = settings.navigationMode,
                        actions = padActions,
                        haptics = haptics,
                        contentDescription = stringResource(R.string.cd_click_pad),
                    )
                    Spacer(Modifier.height(20.dp))
                    if (state.media.canSkipBackward || state.media.canSkipForward) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SkipButton(
                                enabled = state.media.canSkipBackward,
                                seconds = settings.skipBackwardSeconds,
                                forward = false,
                                haptics = haptics,
                                onTap = viewModel::skipBackward,
                            )
                            SkipButton(
                                enabled = state.media.canSkipForward,
                                seconds = settings.skipForwardSeconds,
                                forward = true,
                                haptics = haptics,
                                onTap = viewModel::skipForward,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        RemoteButton(
                            contentDescription = stringResource(R.string.cd_back),
                            onTap = { viewModel.press(HidButton.MENU) },
                            onLongPress = { viewModel.press(HidButton.HOME) },
                            haptics = haptics,
                        ) { ButtonIcon(Icons.Default.ChevronLeft, size = 36.dp) }
                        RemoteButton(
                            contentDescription = stringResource(R.string.cd_home),
                            onTap = { viewModel.press(HidButton.HOME) },
                            onLongPress = { viewModel.hold(HidButton.HOME, BUTTON_HOLD_MS) },
                            haptics = haptics,
                        ) { ButtonIcon(Icons.Default.Tv, size = 28.dp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            RemoteButton(
                                contentDescription = stringResource(R.string.cd_play_pause),
                                onTap = { viewModel.press(HidButton.PLAY_PAUSE) },
                                haptics = haptics,
                            ) { PlayPauseGlyph() }
                            RemoteButton(
                                contentDescription = stringResource(R.string.cd_mute),
                                onTap = { viewModel.press(HidButton.MUTE) },
                                haptics = haptics,
                            ) { ButtonIcon(Icons.Default.VolumeOff, size = 28.dp) }
                        }
                        VolumeRocker(
                            onUp = { viewModel.press(HidButton.VOLUME_UP) },
                            onDown = { viewModel.press(HidButton.VOLUME_DOWN) },
                            upDescription = stringResource(R.string.cd_volume_up),
                            downDescription = stringResource(R.string.cd_volume_down),
                            height = 152.dp,
                            haptics = haptics,
                        )
                    }
                }
            }
        }
    }
    if (showKeyboard) {
        TextInputSheet(
            keyboard = state.keyboard,
            onSend = viewModel::sendText,
            onPress = viewModel::press,
            onRefresh = viewModel::refreshKeyboard,
            onDismiss = { showKeyboard = false },
        )
    }
}

@Composable
private fun SkipButton(enabled: Boolean, seconds: Int, forward: Boolean, haptics: Boolean, onTap: () -> Unit) {
    val description = stringResource(if (forward) R.string.cd_skip_forward else R.string.cd_skip_backward, seconds)
    Box(modifier = Modifier.width(68.dp), contentAlignment = Alignment.Center) {
        if (enabled) {
            RemoteButton(contentDescription = description, onTap = onTap, size = 56.dp, haptics = haptics) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ButtonIcon(if (forward) Icons.Default.FastForward else Icons.Default.FastRewind, size = 22.dp)
                    Text("$seconds", color = RemoteColors.OnButton, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    ConnectionState.Ready -> stringResource(R.string.state_connected)
    is ConnectionState.Failed -> stringResource(R.string.state_failed)
}

/** What the wake-up waits for, with a running count of seconds: a TV that takes a minute to start must not look stuck. */
@Composable
fun wakeLabel(progress: WakeProgress?): String {
    val startedAt = progress?.startedAtMs
    val seconds by produceState(0, startedAt) {
        while (startedAt != null) {
            value = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }
    return when (progress?.stage) {
        null -> stringResource(R.string.state_waking_tv)
        WakeStage.LG_TV -> stringResource(R.string.state_waking_lg_tv, seconds)
        WakeStage.APPLE_TV -> stringResource(R.string.state_waking_apple_tv, seconds)
    }
}

/** One line on why the last connection failed, in words a viewer of the log screen would not need. */
@Composable
fun failureLabel(reason: Throwable): String = when (reason) {
    is CompanionException.ConnectionClosed -> stringResource(R.string.failure_tv_unreachable)
    is PairingException.CredentialsRejected -> stringResource(R.string.failure_pairing_rejected)
    else -> reason.message ?: reason.javaClass.simpleName
}

/** How long a held button stays down: enough for tvOS to open Control Center from the TV button and the app options from OK. */
private const val BUTTON_HOLD_MS = 1000L
