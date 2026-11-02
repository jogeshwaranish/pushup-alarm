package com.pushupalarm

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getStringExtra(AlarmScheduler.EXTRA_KIND)) {
            AlarmScheduler.KIND_ALARM -> {
                val alarm = AlarmStore.get(ctx, intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1))
                if (alarm == null || !alarm.enabled) return
                if (!AlarmScheduler.isDue(alarm)) {
                    AlarmScheduler.schedule(ctx, alarm) // stale trigger: make sure the real time is set, but stay quiet
                    return
                }
                if (alarm.days.isEmpty()) {
                    AlarmStore.save(ctx, alarm.copy(enabled = false)) // one-time alarm is used up
                    AlarmPrefs.clearArmedAt(ctx, alarm.id)
                } else {
                    AlarmScheduler.schedule(ctx, alarm) // its next day
                }
            }
            AlarmScheduler.KIND_TEST -> Unit
            AlarmScheduler.KIND_RESUME -> if (!AlarmPrefs.isRinging(ctx)) return
            else -> return // not one we set (e.g. left over from an older build)
        }
        AlarmPrefs.setRinging(ctx, true)
        AlarmService.start(ctx)
    }
}

/**
 * Alarms are wiped on reboot; re-arm them, and resume ringing if the phone was restarted mid-alarm.
 * Runs before the first unlock as well (LOCKED_BOOT_COMPLETED), so a restart overnight can't lose an alarm.
 * Also re-arms when exact alarms are granted on Android 12/12L, upgrading any inexact fallback.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Must run first: re-arming overwrites the ring times it compares against.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            handleMissed(ctx)
        }
        AlarmScheduler.rescheduleAll(ctx)
        // Android 15+ won't let a boot broadcast start the service, so this goes through an alarm-clock broadcast.
        if (AlarmPrefs.isRinging(ctx)) AlarmScheduler.scheduleResume(ctx)
    }

    private fun handleMissed(ctx: Context) {
        val now = System.currentTimeMillis()
        AlarmStore.all(ctx).filter { it.enabled }.forEach { alarm ->
            when (AlarmScheduler.missed(AlarmPrefs.armedAt(ctx, alarm.id), now)) {
                AlarmScheduler.Missed.RING_LATE -> AlarmPrefs.setRinging(ctx, true)
                AlarmScheduler.Missed.NOTIFY -> AlarmService.notifyMissed(ctx, alarm)
                null -> return@forEach
            }
            if (alarm.days.isEmpty()) AlarmStore.save(ctx, alarm.copy(enabled = false)) // one-time alarm is used up
        }
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Push-up Alarm will no longer be protected from force-stop and uninstall."
}
