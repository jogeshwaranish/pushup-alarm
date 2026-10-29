package com.pushupalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin

class PushupCounterTest {
    /** Side view of a plank: shoulder at origin, hip off to the right, arm bent to [elbowDeg]. */
    private fun plank(elbowDeg: Double, tiltDeg: Double = 10.0, conf: Float = 0.9f): Side {
        val shoulder = P(0f, 0f)
        val t = Math.toRadians(tiltDeg)
        val hip = P((100 * cos(t)).toFloat(), (100 * sin(t)).toFloat())
        val elbow = P(0f, 30f) // upper arm points straight down
        // Forearm leaves the elbow at (180 - elbowDeg) from straight down, angled toward the head (-x).
        val a = Math.toRadians(180 - elbowDeg)
        val wrist = P((elbow.x - 30 * sin(a)).toFloat(), (elbow.y + 30 * cos(a)).toFloat())
        return Side(shoulder, elbow, wrist, hip, conf)
    }

    private var clock = 0L

    /** Front view: shoulders 40 apart, wrists [extension] of a 56-high lockout below them. Hips null = hidden. */
    private fun front(extension: Double, hipsBelowShoulders: Float? = 10f, conf: Float = 0.9f): Front {
        val y = (56 * extension).toFloat()
        return Front(P(-20f, 0f), P(20f, 0f), P(-20f, y), P(20f, y), hipsBelowShoulders?.let { P(0f, it) }, conf)
    }

    private fun PushupCounter.feed(frame: Frame?, frames: Int = 3): CounterState {
        var s = state
        repeat(frames) {
            clock += 100
            s = update(frame, clock)
        }
        return s
    }

    private fun PushupCounter.frontRep(down: Double = 0.5, hips: Float? = 10f) {
        feed(front(down, hips), 4)
        feed(front(1.0, hips), 4)
    }

    private fun PushupCounter.rep(downDeg: Double = 80.0, side: (Double) -> Side = { plank(it) }) {
        feed(side(downDeg), 4)
        feed(side(175.0), 4)
    }

    @Test fun elbowAngleGeometry() {
        assertEquals(90.0, PushupCounter.elbowAngle(plank(90.0)), 0.5)
        assertEquals(170.0, PushupCounter.elbowAngle(plank(170.0)), 0.5)
    }

    @Test fun countsFullReps() {
        val c = PushupCounter(target = 15)
        c.feed(plank(175.0), 4)
        repeat(15) { c.rep() }
        assertEquals(15, c.state.reps)
        assertEquals(Cue.DONE, c.state.cue)
    }

    @Test fun halfRepsDoNotCount() {
        val c = PushupCounter()
        c.feed(plank(175.0), 4)
        repeat(10) { c.rep(downDeg = 120.0) }
        assertEquals(0, c.state.reps)
    }

    @Test fun mustLockOutBetweenReps() {
        val c = PushupCounter()
        c.feed(plank(175.0), 4)
        repeat(5) {
            c.feed(plank(80.0), 4)
            c.feed(plank(135.0), 4) // never locks out
        }
        assertEquals(0, c.state.reps)
    }

    @Test fun standingCurlsDoNotCount() {
        val c = PushupCounter()
        repeat(10) { c.rep { plank(it, tiltDeg = 80.0) } }
        assertEquals(0, c.state.reps)
        assertEquals(Cue.NOT_HORIZONTAL, c.state.cue)
    }

    @Test fun lowConfidenceIgnored() {
        val c = PushupCounter()
        repeat(10) { c.rep { plank(it, conf = 0.2f) } }
        assertEquals(0, c.state.reps)
    }

    @Test fun losingTrackingMidRepCancelsIt() {
        val c = PushupCounter()
        c.feed(plank(175.0), 4)
        c.feed(plank(80.0), 4)
        c.feed(null, 20) // 2 s out of frame
        c.feed(plank(175.0), 4)
        assertEquals(0, c.state.reps)
    }

    @Test fun singleFrameJitterIgnored() {
        val c = PushupCounter()
        c.feed(plank(175.0), 4)
        repeat(10) {
            c.feed(plank(60.0), 1)
            c.feed(plank(175.0), 4)
        }
        assertEquals(0, c.state.reps)
    }

    @Test fun countsFrontReps() {
        val c = PushupCounter(target = 15)
        c.feed(front(1.0), 4)
        repeat(15) { c.frontRep() }
        assertEquals(15, c.state.reps)
    }

    @Test fun countsFrontRepsWithHipsHidden() {
        val c = PushupCounter()
        c.feed(front(1.0, null), 4)
        repeat(5) { c.frontRep(hips = null) }
        assertEquals(5, c.state.reps)
    }

    @Test fun frontShallowRepsDoNotCount() {
        val c = PushupCounter()
        c.feed(front(1.0), 4)
        repeat(10) { c.frontRep(down = 0.8) }
        assertEquals(0, c.state.reps)
    }

    @Test fun frontStandingCurlsDoNotCount() {
        val c = PushupCounter()
        repeat(10) { c.frontRep(hips = 52f) } // torso 1.3 shoulder-widths tall: upright
        assertEquals(0, c.state.reps)
        assertEquals(Cue.NOT_HORIZONTAL, c.state.cue)
    }

    @Test fun frontGlitchFrameCannotRaiseLockoutOutOfReach() {
        val c = PushupCounter()
        c.feed(front(1.0), 4)
        c.feed(front(3.0), 1) // one wild frame
        repeat(5) { c.frontRep() }
        assertEquals(5, c.state.reps)
    }

    @Test fun switchingViewsKeepsRepsButRestartsTheRep() {
        val c = PushupCounter()
        c.feed(plank(175.0), 4)
        repeat(3) { c.rep() }
        c.feed(plank(80.0), 4) // half a rep from the side...
        c.feed(front(1.0), 4) // ...then switch to front: shouldn't count as finishing it
        assertEquals(3, c.state.reps)
        repeat(2) { c.frontRep() }
        assertEquals(5, c.state.reps)
    }

    @Test fun staleTriggerIsNotDue() {
        val zone = ZoneId.of("America/New_York")
        val alarm = Alarm(1, 7, 30, DayOfWeek.entries.toSet())
        assertEquals(true, AlarmScheduler.isDue(alarm, ZonedDateTime.of(2026, 10, 29, 7, 30, 0, 50_000_000, zone)))
        assertEquals(true, AlarmScheduler.isDue(alarm, ZonedDateTime.of(2026, 10, 29, 7, 35, 0, 0, zone)))
        assertEquals(false, AlarmScheduler.isDue(alarm, ZonedDateTime.of(2026, 10, 29, 7, 0, 0, 0, zone))) // old 7:00
        assertEquals(false, AlarmScheduler.isDue(alarm, ZonedDateTime.of(2026, 10, 29, 8, 0, 0, 0, zone)))
        val weekdays = Alarm(2, 7, 30, setOf(DayOfWeek.MONDAY))
        assertEquals(false, AlarmScheduler.isDue(weekdays, ZonedDateTime.of(2026, 10, 29, 7, 30, 0, 0, zone))) // Thursday
    }

    @Test fun oneTimeAlarmRollsToTomorrow() {
        val now = ZonedDateTime.of(2026, 10, 29, 8, 0, 0, 0, ZoneId.of("America/New_York")) // Thursday
        assertEquals(now.withHour(9), AlarmScheduler.nextTrigger(Alarm(1, 9, 0), now))
        assertEquals(now.withHour(7).plusDays(1), AlarmScheduler.nextTrigger(Alarm(1, 7, 0), now))
        assertEquals(now.plusDays(1), AlarmScheduler.nextTrigger(Alarm(1, 8, 0), now))
    }

    @Test fun repeatingAlarmSkipsToItsNextDay() {
        val now = ZonedDateTime.of(2026, 10, 29, 8, 0, 0, 0, ZoneId.of("America/New_York")) // Thursday
        val mwf = Alarm(1, 7, 0, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        assertEquals(now.withHour(7).plusDays(1), AlarmScheduler.nextTrigger(mwf, now)) // Fri
        val thursdays = Alarm(2, 7, 0, setOf(DayOfWeek.THURSDAY))
        assertEquals(now.withHour(7).plusDays(7), AlarmScheduler.nextTrigger(thursdays, now)) // already passed today
    }
}
