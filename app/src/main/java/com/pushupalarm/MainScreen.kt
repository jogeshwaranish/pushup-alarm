package com.pushupalarm

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.style.TextAlign
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val Red = Color(0xFFE5322D)
private val Ink = Color(0xFFF4F1EC)
private val Muted = Color(0xFF9C978F)
private val Card = Color(0xFF1A1A1D)
private val Ok = Color(0xFF5FB97A)

private val colors = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    background = Color(0xFF0E0E10),
    surface = Card,
    onSurface = Ink,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val activity = ctx as Activity

    var alarms by remember { mutableStateOf(AlarmStore.all(ctx)) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var locked by remember { mutableStateOf<Alarm?>(null) }
    var confirmTest by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(AlarmPrefs.volumePercent(ctx).toFloat()) }
    var cameraAngle by remember { mutableStateOf(AlarmPrefs.cameraAngle(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val items = remember(resumeTick) { Setup.items(ctx) }
    val ready = items.filter { it.required }.all { it.granted }

    val otherAlarm = remember(resumeTick, alarms) { OtherAlarms.next(ctx) }

    fun save(alarm: Alarm) {
        AlarmStore.save(ctx, alarm)
        AlarmScheduler.schedule(ctx, alarm)
        alarms = AlarmStore.all(ctx)
    }

    LaunchedEffect(resumeTick) {
        alarms = AlarmStore.all(ctx)
        cameraAngle = AlarmPrefs.cameraAngle(ctx) // may have been switched on the push-up screen
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }

    MaterialTheme(colorScheme = colors) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("PUSH-UP ALARM", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(28.dp))

            val next = remember(alarms, now) { AlarmScheduler.next(ctx) }
            Text(
                next?.let { (_, at) -> "Next ${at.format(DateTimeFormatter.ofPattern("EEE h:mm a"))}" } ?: "No alarms on",
                color = Ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                next?.let { (_, at) -> untilText(at, now) } ?: "Add one below",
                color = Muted,
                fontSize = 15.sp,
            )

            otherAlarm?.let { other ->
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF3A2A0A))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        "${other.appName} also has an alarm at " +
                            Instant.ofEpochMilli(other.atMs).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("EEE h:mm a")),
                        color = Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Turn it off there so the two don't fight. Android only shows the next one, so check for more.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                    TextButton(onClick = { OtherAlarms.open(ctx, other) }) { Text("Open ${other.appName}") }
                }
            }

            Spacer(Modifier.height(24.dp))
            alarms.forEach { alarm ->
                AlarmRow(
                    alarm = alarm,
                    onClick = { editing = alarm },
                    onToggle = { on ->
                        if (on) {
                            if (ready) save(alarm.copy(enabled = true))
                        } else {
                            locked = alarm
                        }
                    },
                    canEnable = ready,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { editing = Alarm(AlarmStore.nextId(ctx), 7, 0) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (ready) "+ Add alarm" else "Finish setup below to add alarms", fontSize = 16.sp) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { ctx.startActivity(AlarmActivity.intent(ctx, AlarmActivity.Mode.PRACTICE)) },
                    enabled = items.first().granted,
                    modifier = Modifier.weight(1f),
                ) { Text("Practice", color = Ink) }
                OutlinedButton(
                    onClick = { confirmTest = true },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Text("Test ring", color = Ink) }
            }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VOLUME", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("${volume.roundToInt()}%", color = Ink, fontSize = 13.sp)
            }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    AlarmPrefs.setVolumePercent(ctx, volume.roundToInt())
                    AlarmSound.preview(ctx)
                },
                valueRange = AlarmSound.MIN_PERCENT.toFloat()..100f,
                colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red),
            )
            Text(
                "While it rings you can turn it down to ${AlarmSound.MIN_PERCENT}%, never off. Release the slider to hear it.",
                color = Muted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(28.dp))
            Text("CAMERA ANGLE", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CameraAngle.entries.forEach { angle ->
                    val label = if (angle == CameraAngle.SIDE) "Side" else "Front"
                    val pick = {
                        cameraAngle = angle
                        AlarmPrefs.setCameraAngle(ctx, angle)
                    }
                    if (angle == cameraAngle) {
                        Button(onClick = pick, modifier = Modifier.weight(1f)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) { Text(label, color = Ink) }
                    }
                }
            }
            Text(
                if (cameraAngle == CameraAngle.SIDE) {
                    "Phone on the floor about 2 m to your side. Most accurate."
                } else {
                    "Phone on the floor about 1.5 m in front of your head, facing you."
                } + " Portrait or landscape both work, and you can switch on the push-up screen.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(28.dp))
            Text("SETUP", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            items.forEach { item ->
                SetupRow(item) { item.fix(activity) }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Once it rings, the only way out is ${AlarmActivity.TARGET} real push-ups: elbows past 100°, " +
                    "arms locked out, body horizontal. Volume can't go below ${AlarmSound.MIN_PERCENT}%, and leaving the screen " +
                    "brings it straight back. Restarting the phone resumes the alarm.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(Modifier.height(28.dp))
            Text("DELETE APP", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            when (val gate = DeleteGate.state(ctx, now)) {
                DeleteGate.State.Locked -> OutlinedButton(
                    onClick = { ctx.startActivity(AlarmActivity.intent(ctx, AlarmActivity.Mode.DELETE)) },
                    enabled = items.first().granted,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock · ${DeleteGate.PUSHUPS} push-ups, then a 1 h wait", color = Ink) }

                is DeleteGate.State.CoolingDown -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Delete unlocks in ${ceil(gate.remainingMs / 60_000.0).toInt()} min",
                        color = Ink,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        DeleteGate.cancel(ctx)
                        now = System.currentTimeMillis()
                    }) { Text("Cancel") }
                }

                DeleteGate.State.Unlocked -> {
                    Button(
                        onClick = { DeleteGate.uninstall(activity) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Delete app now") }
                    Text("Locks again 24 h after unlocking.", color = Muted, fontSize = 12.sp)
                }
            }
        }

        editing?.let { alarm ->
            AlarmEditDialog(
                alarm = alarm,
                isNew = alarms.none { it.id == alarm.id },
                onSave = { updated ->
                    save(updated.copy(enabled = true))
                    editing = null
                    Toast.makeText(ctx, untilText(AlarmScheduler.nextTrigger(updated), System.currentTimeMillis()), Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    if (alarm.enabled) {
                        ctx.startActivity(AlarmActivity.disarmIntent(ctx, alarm.id, delete = true))
                    } else {
                        AlarmScheduler.cancel(ctx, alarm.id)
                        AlarmStore.delete(ctx, alarm.id)
                        alarms = AlarmStore.all(ctx)
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }

        locked?.let { alarm ->
            AlertDialog(
                onDismissRequest = { locked = null },
                title = { Text("${timeText(alarm)} is on") },
                text = {
                    Text("Turning it off or deleting it takes ${AlarmActivity.TARGET} push-ups.")
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            ctx.startActivity(AlarmActivity.disarmIntent(ctx, alarm.id, delete = true))
                            locked = null
                        }) { Text("Delete") }
                        TextButton(onClick = {
                            ctx.startActivity(AlarmActivity.disarmIntent(ctx, alarm.id, delete = false))
                            locked = null
                        }) { Text("Turn off") }
                    }
                },
                dismissButton = { TextButton(onClick = { locked = null }) { Text("Cancel") } },
            )
        }

        if (confirmTest) {
            AlertDialog(
                onDismissRequest = { confirmTest = false },
                title = { Text("This is a real alarm") },
                text = { Text("It rings in 10 seconds and won't stop until you do ${AlarmActivity.TARGET} push-ups.") },
                confirmButton = {
                    TextButton(onClick = {
                        AlarmScheduler.scheduleTest(ctx)
                        confirmTest = false
                        Toast.makeText(ctx, "Ringing in 10 seconds", Toast.LENGTH_SHORT).show()
                    }) { Text("Ring it") }
                },
                dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem, onFix: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title + if (item.required) "" else "  · recommended",
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(item.why, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        if (item.granted) {
            Text("✓", color = Ok, fontSize = 20.sp, modifier = Modifier.padding(start = 12.dp))
        } else {
            Button(
                onClick = onFix,
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("Grant") }
        }
    }
}

private fun untilText(at: ZonedDateTime, nowMs: Long): String {
    val d = Duration.between(Instant.ofEpochMilli(nowMs), at.toInstant())
    return "Rings in ${d.toHours()}h ${d.toMinutesPart()}m"
}

private fun timeText(alarm: Alarm) = LocalTime.of(alarm.hour, alarm.minute).format(DateTimeFormatter.ofPattern("h:mm a"))

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

private fun daysText(days: Set<DayOfWeek>) = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == weekdays -> "Weekdays"
    days == weekend -> "Weekends"
    else -> DayOfWeek.entries.filter { it in days }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

@Composable
private fun AlarmRow(alarm: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit, canEnable: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(timeText(alarm), color = if (alarm.enabled) Ink else Muted, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                daysText(alarm.days),
                color = Muted,
                fontSize = 13.sp,
            )
        }
        Switch(
            checked = alarm.enabled,
            onCheckedChange = onToggle,
            enabled = alarm.enabled || canEnable,
            colors = SwitchDefaults.colors(checkedTrackColor = Red),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    alarm: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = false)
    var days by remember { mutableStateOf(alarm.days) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text(if (alarm.enabled) "Delete · ${AlarmActivity.TARGET} push-ups" else "Delete")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = time)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val on = day in days
                        Text(
                            day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            color = if (on) Color.White else Muted,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (on) Red else Card)
                                .clickable { days = if (on) days - day else days + day }
                                .wrapContentHeight(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(daysText(days), color = Muted, fontSize = 13.sp)
            }
        },
    )
}
