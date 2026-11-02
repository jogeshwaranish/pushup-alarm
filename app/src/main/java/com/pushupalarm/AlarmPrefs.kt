package com.pushupalarm

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Preferences kept in device-protected storage, because alarms have to work after a restart before
 * the first unlock (an overnight system update, say), when normal app storage can't be read yet.
 * Preferences saved by older versions are moved over the first time they're opened while unlocked.
 */
object DeviceStorage {
    private val moved = mutableSetOf<String>()

    fun isUnlocked(ctx: Context) = ctx.getSystemService(UserManager::class.java).isUserUnlocked

    fun prefs(ctx: Context, name: String): SharedPreferences {
        val device = ctx.createDeviceProtectedStorageContext()
        synchronized(moved) {
            if (name !in moved && isUnlocked(ctx) && device.moveSharedPreferencesFrom(ctx, name)) moved += name
        }
        return device.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}

object AlarmPrefs {
    private fun prefs(ctx: Context) = DeviceStorage.prefs(ctx, "alarm")

    fun volumePercent(ctx: Context) = prefs(ctx).getInt("volume", 100)
    fun setVolumePercent(ctx: Context, percent: Int) = prefs(ctx).edit().putInt("volume", percent).apply()

    fun cameraAngle(ctx: Context) =
        CameraAngle.entries.firstOrNull { it.name == prefs(ctx).getString("cameraAngle", null) } ?: CameraAngle.SIDE
    fun setCameraAngle(ctx: Context, angle: CameraAngle) = prefs(ctx).edit().putString("cameraAngle", angle.name).apply()

    /** When deleting the app becomes allowed; 0 means no delete has been requested. */
    fun deleteUnlockAt(ctx: Context) = prefs(ctx).getLong("deleteUnlockAt", 0L)
    fun setDeleteUnlockAt(ctx: Context, atMs: Long) = prefs(ctx).edit().putLong("deleteUnlockAt", atMs).apply()

    /** Whether the phone maker's auto-start screen was opened from Setup; what was chosen there can't be read. */
    fun autoStartOpened(ctx: Context) = prefs(ctx).getBoolean("autoStartOpened", false)
    fun setAutoStartOpened(ctx: Context) = prefs(ctx).edit().putBoolean("autoStartOpened", true).apply()

    /** Persisted so a reboot, crash or kill resumes the alarm instead of silencing it. */
    fun isRinging(ctx: Context) = prefs(ctx).getBoolean("ringing", false)
    fun setRinging(ctx: Context, ringing: Boolean) = prefs(ctx).edit().putBoolean("ringing", ringing).commit()

    /** When an alarm is next set to ring, or 0. Lets a restart tell that a ring time passed while the phone was off. */
    fun armedAt(ctx: Context, alarmId: Int) = prefs(ctx).getLong("armedAt$alarmId", 0L)
    fun setArmedAt(ctx: Context, alarmId: Int, atMs: Long) = prefs(ctx).edit().putLong("armedAt$alarmId", atMs).commit()
    fun clearArmedAt(ctx: Context, alarmId: Int) = prefs(ctx).edit().remove("armedAt$alarmId").commit()
}
