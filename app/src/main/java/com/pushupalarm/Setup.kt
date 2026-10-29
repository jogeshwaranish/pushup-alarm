package com.pushupalarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class SetupItem(
    val title: String,
    val why: String,
    val required: Boolean,
    val granted: Boolean,
    val fix: (Activity) -> Unit,
)

/** Everything the alarm needs to be inescapable, and how to grant each. */
object Setup {
    fun items(ctx: Context): List<SetupItem> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        return listOf(
            SetupItem(
                "Camera", "Counts your push-ups. Nothing is recorded or saved.", true,
                ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            ) { it.requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) },
            SetupItem(
                "Notifications", "Required for the alarm to ring in the background.", true,
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) { it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) },
            SetupItem(
                "Full-screen alerts", "Takes over the lock screen when the alarm fires.", true,
                ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            ) { it.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) },
            SetupItem(
                "Display over other apps", "Pulls the alarm back up if you try to leave it.", true,
                Settings.canDrawOverlays(ctx),
            ) { it.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) },
            SetupItem(
                "Unrestricted battery", "Stops Samsung from putting the alarm to sleep.", false,
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            ) { it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) },
            SetupItem(
                "Device admin", "Disables Force stop and Uninstall, so you can't kill it.", false,
                ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin),
            ) {
                it.startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Blocks Force stop and Uninstall so the alarm can't be killed.",
                        ),
                )
            },
        )
    }
}
