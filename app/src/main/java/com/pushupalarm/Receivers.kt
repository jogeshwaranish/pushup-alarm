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
 * Also re-arms when exact alarms are granted on Android 12/12L, upgrading any inexact fallback.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
        if (AlarmPrefs.isRinging(ctx)) AlarmScheduler.scheduleResume(ctx)
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Push-up Alarm will no longer be protected from force-stop and uninstall."
}
