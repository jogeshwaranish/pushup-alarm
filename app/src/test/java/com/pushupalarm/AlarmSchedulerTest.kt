package com.pushupalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmSchedulerTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val weekend = setOf(SATURDAY, SUNDAY)

    private fun at(text: String) = ZonedDateTime.parse("$text[America/Chicago]")

    @Test
    fun weekdayAlarmSetBeforeClocksGoBackRingsAtEightLocalTime() {
        // Friday Oct 30 2026, still daylight time. Clocks go back on Sunday Nov 1.
        val next = AlarmScheduler.nextTrigger(Alarm(1, 8, 0, weekdays), at("2026-10-30T08:00:01-05:00"))
        assertEquals(at("2026-11-02T08:00:00-06:00"), next)
    }

    @Test
    fun weekendAlarmOnTheDayClocksGoBackRingsAtTenLocalTime() {
        val next = AlarmScheduler.nextTrigger(Alarm(1, 10, 0, weekend), at("2026-10-31T23:00:00-05:00"))
        assertEquals(at("2026-11-01T10:00:00-06:00"), next)
    }

    @Test
    fun weekdayAndWeekendAlarmsEachKeepToTheirOwnDays() {
        val mondayNight = at("2026-11-02T23:00:00-06:00")
        assertEquals(at("2026-11-03T08:00:00-06:00"), AlarmScheduler.nextTrigger(Alarm(2, 8, 0, weekdays), mondayNight))
        assertEquals(at("2026-11-07T10:00:00-06:00"), AlarmScheduler.nextTrigger(Alarm(1, 10, 0, weekend), mondayNight))
    }

    @Test
    fun alarmInTheSkippedHourOnlyMovesOnTheDayClocksGoForward() {
        // 2:30 doesn't exist on Sunday Mar 14 2027, so it rings at 3:30 that day and at 2:30 again the next.
        val alarm = Alarm(1, 2, 30, setOf(SUNDAY, MONDAY))
        assertEquals(
            at("2027-03-14T03:30:00-05:00"),
            AlarmScheduler.nextTrigger(alarm, at("2027-03-13T23:00:00-06:00")),
        )
        assertEquals(
            at("2027-03-15T02:30:00-05:00"),
            AlarmScheduler.nextTrigger(alarm, at("2027-03-14T03:30:01-05:00")),
        )
    }

    @Test
    fun oneTimeAlarmRingsAtTheNextOccurrenceOfItsTime() {
        val now = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, chicago)
        assertEquals(now.withHour(10), AlarmScheduler.nextTrigger(Alarm(1, 10, 0), now))
        assertEquals(now.plusDays(1).withHour(8), AlarmScheduler.nextTrigger(Alarm(1, 8, 0), now))
    }

    @Test
    fun aDeliveredAlarmIsDueButAStaleOneIsNot() {
        val alarm = Alarm(1, 8, 0, weekdays)
        assertTrue(AlarmScheduler.isDue(alarm, at("2026-11-02T08:00:00.010-06:00")))
        assertFalse(AlarmScheduler.isDue(alarm, at("2026-11-02T08:30:00-06:00")))
    }

    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour

    @Test
    fun anAlarmStillToComeIsNotMissed() {
        assertNull(AlarmScheduler.missed(armedAtMs = now + 1, nowMs = now))
    }

    @Test
    fun anAlarmNeverArmedIsNotMissed() {
        assertNull(AlarmScheduler.missed(armedAtMs = 0, nowMs = now))
    }

    @Test
    fun anAlarmMissedRecentlyRingsLate() {
        assertEquals(AlarmScheduler.Missed.RING_LATE, AlarmScheduler.missed(armedAtMs = now, nowMs = now))
        assertEquals(AlarmScheduler.Missed.RING_LATE, AlarmScheduler.missed(armedAtMs = now - 2 * hour, nowMs = now))
    }

    @Test
    fun anAlarmMissedLongAgoOnlyNotifies() {
        assertEquals(AlarmScheduler.Missed.NOTIFY, AlarmScheduler.missed(armedAtMs = now - 2 * hour - 1, nowMs = now))
    }
}
