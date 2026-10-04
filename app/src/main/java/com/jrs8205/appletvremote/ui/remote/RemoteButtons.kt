package com.jrs8205.appletvremote.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jrs8205.appletvremote.ui.theme.RemoteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

@Composable
fun buttonColor(): Color = if (isSystemInDarkTheme()) RemoteColors.ButtonDark else RemoteColors.ButtonLight

/**
 * A round remote button with tap, optional long press and a haptic tick. [onLongPressEnd] follows a long
 * press when the finger lifts, or when the gesture ends any other way.
 */
@Composable
fun RemoteButton(
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    onLongPress: (() -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val longPressEnd by rememberUpdatedState(onLongPressEnd)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { val s = if (pressed) 0.92f else 1f; scaleX = s; scaleY = s }
            .background(buttonColor(), CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                // The gestures below never reach a screen reader; these actions do.
                onClick { tap(); true }
                // A screen reader's long press has no release to wait for, so it ends at once.
                longPress?.let { handler -> onLongClick { handler(); longPressEnd?.invoke(); true } }
            }
            .pointerInput(haptics) {
                var longPressed = false
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            if (longPressed) {
                                longPressed = false
                                longPressEnd?.invoke()
                            }
                        }
                    },
                    onTap = {
                        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        tap()
                    },
                    onLongPress = longPress?.let { handler ->
                        {
                            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPressed = true
                            handler()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun ButtonIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Icon(icon, contentDescription = null, tint = RemoteColors.OnButton, modifier = modifier.size(size))
}

/** The combined play/pause glyph the physical remote carries. */
@Composable
fun PlayPauseGlyph(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier = modifier.size(size)) {
        val h = this.size.height
        val w = this.size.width
        val triangle = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.5f, h / 2f)
            lineTo(0f, h)
            close()
        }
        drawPath(triangle, RemoteColors.OnButton)
        val barWidth = w * 0.16f
        drawRect(RemoteColors.OnButton, topLeft = Offset(w * 0.6f, 0f), size = androidx.compose.ui.geometry.Size(barWidth, h))
        drawRect(RemoteColors.OnButton, topLeft = Offset(w * 0.84f, 0f), size = androidx.compose.ui.geometry.Size(barWidth, h))
    }
}

/** Tall +/− rocker: a tap sends one step, holding repeats after a short delay. */
@Composable
fun VolumeRocker(
    onUp: () -> Unit,
    onDown: () -> Unit,
    upDescription: String,
    downDescription: String,
    modifier: Modifier = Modifier,
    width: Dp = 68.dp,
    height: Dp = 150.dp,
    haptics: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    Column(
        modifier = modifier
            .width(width)
            .height(height)
            .background(buttonColor(), RoundedCornerShape(percent = 50)),
    ) {
        RockerHalf(
            description = upDescription,
            haptics = haptics,
            onStep = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.ContextClick); up() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { Text("+", color = RemoteColors.OnButton, style = MaterialTheme.typography.headlineSmall) }
        RockerHalf(
            description = downDescription,
            haptics = haptics,
            onStep = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.ContextClick); down() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { Text("−", color = RemoteColors.OnButton, style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun RockerHalf(
    description: String,
    haptics: Boolean,
    onStep: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    Box(
        modifier = modifier
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { step(); true }
            }
            .pointerInput(haptics) {
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            val repeater = launch {
                                step()
                                delay(REPEAT_DELAY_MS)
                                while (isActive) {
                                    step()
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 150L

/** Hit-testing helper shared by the click pad: which region of a circle a point falls in. */
enum class PadRegion { CENTER, UP, DOWN, LEFT, RIGHT }

fun padRegion(point: Offset, bounds: Rect, centerFraction: Float = 0.38f): PadRegion {
    val dx = point.x - bounds.center.x
    val dy = point.y - bounds.center.y
    val radius = minOf(bounds.width, bounds.height) / 2f
    if (dx * dx + dy * dy <= (radius * centerFraction) * (radius * centerFraction)) return PadRegion.CENTER
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx > 0) PadRegion.RIGHT else PadRegion.LEFT
    } else {
        if (dy > 0) PadRegion.DOWN else PadRegion.UP
    }
}
