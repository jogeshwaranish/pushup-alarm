package com.pushupalarm

import android.content.Context

object AlarmPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alarm", Context.MODE_PRIVATE)

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
}
