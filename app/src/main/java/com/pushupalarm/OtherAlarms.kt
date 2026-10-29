package com.pushupalarm

import android.app.AlarmManager
import android.content.Context

/**
 * Android won't let one app read or edit another's alarms, but it does expose the phone's next
 * alarm and which app set it. Used to warn about a clock app that would ring alongside this one.
 */
object OtherAlarms {
    data class Found(val packageName: String, val appName: String, val atMs: Long)

    fun next(ctx: Context): Found? {
        val info = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val pkg = info.showIntent?.creatorPackage ?: return null
        if (pkg == ctx.packageName) return null
        val name = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        return Found(pkg, name, info.triggerTime)
    }

    fun open(ctx: Context, found: Found) {
        ctx.packageManager.getLaunchIntentForPackage(found.packageName)?.let(ctx::startActivity)
    }
}
