package com.pushupalarm

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

private val Red = Color(0xFFE5322D)
private val Ink = Color(0xFFF4F1EC)
private val Muted = Color(0xFF9C978F)
private val Bg = Color(0xFF0E0E10)
private val BgAlarm = Color(0xFF3A0B0A)

@Composable
fun AlarmScreen(
    mode: AlarmActivity.Mode,
    state: CounterState,
    finished: Boolean,
    hasCamera: Boolean,
    cameraError: String?,
    cameraAngle: CameraAngle,
    volume: Int,
    onRequestCamera: () -> Unit,
    previewFactory: (Context) -> View,
    onSwitchAngle: () -> Unit,
    onVolume: (Int) -> Unit,
    onEmergency: () -> Unit,
    onCancel: () -> Unit,
) {
    val ringing = mode == AlarmActivity.Mode.ALARM && !finished
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val front = cameraAngle == CameraAngle.FRONT
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulsed by pulse.animateColor(
        initialValue = Bg,
        targetValue = BgAlarm,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "bg",
    )

    CameraAndPanel(
        landscape = landscape,
        modifier = Modifier
            .fillMaxSize()
            .background(if (ringing) pulsed else Bg)
            .safeDrawingPadding() // if the system bars do show (swiped in, or older Android), keep the buttons clear of them
            .padding(20.dp),
        camera = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (hasCamera) {
                    AndroidView(factory = previewFactory, modifier = Modifier.fillMaxSize())
                    cameraError?.let {
                        Text("Camera error: $it", color = Ink, modifier = Modifier.padding(16.dp))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera access is needed to count reps", color = Ink)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRequestCamera) { Text("Allow camera") }
                    }
                }
            }
        },
        panel = {
            Column(
                if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().wrapContentHeight(),
                verticalArrangement = if (landscape) Arrangement.SpaceBetween else Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when (mode) {
                        AlarmActivity.Mode.ALARM -> "WAKE UP"
                        AlarmActivity.Mode.DISARM -> "DISARM ALARM"
                        AlarmActivity.Mode.PRACTICE -> "PRACTICE"
                        AlarmActivity.Mode.DELETE -> "UNLOCK DELETE"
                    },
                    color = if (mode == AlarmActivity.Mode.ALARM) Red else Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )

                Column {
                    val big = if (landscape) 112.sp else 80.sp
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${state.reps}", color = Ink, fontSize = big, fontWeight = FontWeight.Black, lineHeight = big)
                        Text(" / ${state.target}", color = Muted, fontSize = 32.sp, modifier = Modifier.padding(bottom = 14.dp))
                    }
                    LinearProgressIndicator(
                        progress = { state.reps / state.target.toFloat() },
                        color = Red,
                        trackColor = Color(0x33FFFFFF),
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(if (landscape) 20.dp else 12.dp))
                    Text(
                        cueText(state.cue, mode, cameraAngle),
                        color = Ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 30.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.arm?.let {
                            if (front) "Arms $it% straight  ·  down < 70%, up > 90%" else "Elbow $it°  ·  down < 100°, up > 150°"
                        } ?: if (front) {
                            "Phone on the floor about 1.5 m in front of your head, facing you"
                        } else {
                            "Phone on the floor, sideways, about 2 m to your side"
                        },
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }

                Column {
                    if (ringing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("VOLUME", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onVolume(-10) }, enabled = volume > AlarmSound.MIN_PERCENT) {
                                Text("−", fontSize = 22.sp)
                            }
                            Text("$volume%", color = Ink, fontSize = 15.sp)
                            TextButton(onClick = { onVolume(10) }, enabled = volume < 100) {
                                Text("+", fontSize = 22.sp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSwitchAngle, colors = ButtonDefaults.textButtonColors(contentColor = Muted)) {
                            Text(if (front) "Use side view" else "Use front view")
                        }
                        Spacer(Modifier.weight(1f))
                        if (mode == AlarmActivity.Mode.ALARM) {
                            TextButton(onClick = onEmergency, colors = ButtonDefaults.textButtonColors(contentColor = Muted)) {
                                Text("Emergency call", textAlign = TextAlign.Start)
                            }
                        } else {
                            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Muted)) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Camera beside the panel in landscape, above it in portrait. It's one layout for both so the
 * camera preview survives the phone being rotated instead of being torn down and rebound.
 */
@Composable
private fun CameraAndPanel(
    landscape: Boolean,
    modifier: Modifier,
    camera: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { camera() }
            Box { panel() }
        },
        modifier = modifier,
    ) { (cam, pan), c ->
        val gap = 24.dp.roundToPx()
        val w = c.maxWidth
        val h = c.maxHeight
        if (landscape) {
            val panelW = ((w - gap) / 2.25f).roundToInt()
            val camera = cam.measure(Constraints.fixed(w - gap - panelW, h))
            val panel = pan.measure(Constraints.fixed(panelW, h))
            layout(w, h) {
                camera.place(0, 0)
                panel.place(w - panelW, 0)
            }
        } else {
            val panel = pan.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h / 2))
            val cameraH = (h - gap - panel.height).coerceAtLeast(0)
            val camera = cam.measure(Constraints.fixed(w, cameraH))
            layout(w, h) {
                camera.place(0, 0)
                panel.place(0, cameraH + gap)
            }
        }
    }
}

private fun cueText(cue: Cue, mode: AlarmActivity.Mode, angle: CameraAngle) = when (cue) {
    Cue.FIND_BODY -> if (angle == CameraAngle.FRONT) "Get your head, shoulders and hands in view" else "Get your whole side in view"
    Cue.NOT_HORIZONTAL -> "Get down into a plank"
    Cue.GET_IN_PLANK -> "Lock your arms out to start"
    Cue.GO_DOWN -> "Down. Chest to the floor."
    Cue.PUSH_UP -> "Push up!"
    Cue.DONE -> when (mode) {
        AlarmActivity.Mode.ALARM -> "Done. Good morning."
        AlarmActivity.Mode.DISARM -> "Alarm disarmed."
        AlarmActivity.Mode.PRACTICE -> "Nice. That's the bar."
        AlarmActivity.Mode.DELETE -> "Delete unlocks in 1 hour."
    }
}
