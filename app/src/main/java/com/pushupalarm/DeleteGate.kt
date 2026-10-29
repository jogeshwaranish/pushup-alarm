package com.pushupalarm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Friction in front of uninstalling: [PUSHUPS] push-ups start a [COOLDOWN_MS] wait, after which
 * deleting is allowed for [WINDOW_MS]. Then it locks again, so it can't be unlocked once and left open.
 */
object DeleteGate {
    const val PUSHUPS = 30
    const val COOLDOWN_MS = 60 * 60 * 1000L
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L

    sealed interface State {
        data object Locked : State
        data class CoolingDown(val remainingMs: Long) : State
        data object Unlocked : State
    }

    fun state(ctx: Context, nowMs: Long): State {
        val unlockAt = AlarmPrefs.deleteUnlockAt(ctx)
        return when {
            unlockAt == 0L || nowMs > unlockAt + WINDOW_MS -> State.Locked
            nowMs < unlockAt -> State.CoolingDown(unlockAt - nowMs)
            else -> State.Unlocked
        }
    }

    fun startCooldown(ctx: Context) = AlarmPrefs.setDeleteUnlockAt(ctx, System.currentTimeMillis() + COOLDOWN_MS)

    fun cancel(ctx: Context) = AlarmPrefs.setDeleteUnlockAt(ctx, 0L)

    /** Drops the device-admin protection (which blocks uninstall) and opens the system uninstall prompt. */
    fun uninstall(activity: Activity) {
        AlarmScheduler.cancelAll(activity)
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(activity, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}")))
    }
}
