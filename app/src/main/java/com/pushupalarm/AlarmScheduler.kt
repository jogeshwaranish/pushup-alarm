package com.pushupalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

object AlarmScheduler {
    const val EXTRA_KIND = "kind"
    const val EXTRA_ALARM_ID = "alarmId"
    const val KIND_ALARM = "alarm"
    const val KIND_TEST = "test"
    const val KIND_RESUME = "resume"

    private const val REQ_TEST = 2
    private const val REQ_RESUME = 3
    private const val REQ_ALARM_BASE = 1000
    private const val DUE_GRACE_MIN = 10L

    fun nextTrigger(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val today = now.withHour(alarm.hour).withMinute(alarm.minute).withSecond(0).withNano(0)
        return (0..7L).map { today.plusDays(it) }
            .first { it.isAfter(now) && (alarm.days.isEmpty() || it.dayOfWeek in alarm.days) }
    }

    /**
     * Whether [alarm] was due in the last few minutes. False for a stale trigger, e.g. one still set
     * for 7:00 after the alarm was moved to 7:30, or one left behind by an older build of the app.
     */
    fun isDue(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        !nextTrigger(alarm, now.minusMinutes(DUE_GRACE_MIN)).isAfter(now)

    /** The soonest enabled alarm and when it rings, or null if none are on. */
    fun next(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): Pair<Alarm, ZonedDateTime>? =
        AlarmStore.all(ctx).filter { it.enabled }
            .map { it to nextTrigger(it, now) }
            .minByOrNull { it.second }

    /** Schedules an enabled alarm's next ring, or cancels a disabled one. */
    fun schedule(ctx: Context, alarm: Alarm) {
        if (!alarm.enabled) return cancel(ctx, alarm.id)
        set(ctx, nextTrigger(alarm).toInstant().toEpochMilli(), alarmPending(ctx, alarm.id))
    }

    fun cancel(ctx: Context, alarmId: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmPending(ctx, alarmId))
    }

    fun rescheduleAll(ctx: Context) = AlarmStore.all(ctx).forEach { schedule(ctx, it) }

    fun cancelAll(ctx: Context) = AlarmStore.all(ctx).forEach { cancel(ctx, it.id) }

    fun scheduleTest(ctx: Context, delayMs: Long = 10_000) =
        set(ctx, System.currentTimeMillis() + delayMs, pending(ctx, REQ_TEST, KIND_TEST))

    /** Brings a ringing alarm back after it was killed. Alarm-clock broadcasts may start foreground services. */
    fun scheduleResume(ctx: Context, delayMs: Long = 2_000) =
        set(ctx, System.currentTimeMillis() + delayMs, pending(ctx, REQ_RESUME, KIND_RESUME))

    private fun set(ctx: Context, atMs: Long, operation: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), operation)
    }

    private fun alarmPending(ctx: Context, alarmId: Int) =
        pending(ctx, REQ_ALARM_BASE + alarmId, KIND_ALARM, alarmId)

    private fun pending(ctx: Context, requestCode: Int, kind: String, alarmId: Int = -1): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            requestCode,
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
