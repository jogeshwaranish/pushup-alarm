package com.pushupalarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class SetupItem(
    val title: String,
    val why: String,
    val required: Boolean,
    val granted: Boolean,
    val fix: (Activity) -> Unit,
)

/** Everything the alarm needs to be inescapable, and how to grant each. The camera stays first. */
object Setup {
    fun items(ctx: Context): List<SetupItem> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        return listOfNotNull(
            SetupItem(
                "Camera", "Counts your push-ups. Nothing is recorded or saved.", true,
                ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            ) { it.requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) },
            // Before Android 13 there's no permission to ask for: notifications are on unless switched off in Settings.
            SetupItem(
                "Notifications", "Required for the alarm to ring in the background.", true,
                NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                } else {
                    it.open(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
                    )
                }
            },
            // Android 12 and 12L make exact alarms opt-in, and USE_EXACT_ALARM (auto-granted) only exists from 13.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                SetupItem(
                    "Alarms & reminders", "Lets the alarm ring on the exact minute.", true,
                    ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                ) { it.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
            } else null,
            // Granted automatically before Android 14.
            SetupItem(
                "Full-screen alerts", "Takes over the lock screen when the alarm fires.", true,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            ) { it.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) },
            SetupItem(
                "Display over other apps", "Pulls the alarm back up if you try to leave it.", true,
                Settings.canDrawOverlays(ctx),
            ) {
                it.open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            },
            SetupItem(
                "Unrestricted battery", "Stops the phone from putting the alarm to sleep.", false,
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            ) {
                it.open(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            },
            // There's no API to read the maker's toggle, so it counts as done once the screen has been opened.
            AutoStart.intent(ctx)?.let { screen ->
                SetupItem(
                    "Auto-start", "This phone's maker blocks apps from waking in the background. Turn it on here.", false,
                    AlarmPrefs.autoStartOpened(ctx),
                ) {
                    AlarmPrefs.setAutoStartOpened(it)
                    it.open(screen)
                }
            },
            SetupItem(
                "Device admin", "Disables Force stop and Uninstall, so you can't kill it.", false,
                ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin),
            ) {
                it.open(
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

    /** Opens the first of [intents] the phone has. Makers drop or move Settings screens, and a missing one mustn't crash. */
    private fun Activity.open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}

/**
 * Some makers (Xiaomi, Huawei, Oppo, Vivo...) add their own auto-start blocker on top of Android's
 * battery settings, which stops alarms and boot rescheduling. Each package must also be in the
 * manifest's `<queries>` to be found on Android 11+.
 */
private object AutoStart {
    private val screens = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity", // Xiaomi, Redmi, POCO
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity", // Letv
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // Huawei, Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity", // Oppo, Realme
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity", // Vivo, iQOO
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity", // OnePlus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity", // Asus
        "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity", // Nokia
    )

    /** This phone's auto-start screen, or null on phones without one (Pixel, Samsung, stock Android). */
    fun intent(ctx: Context): Intent? = screens
        .map { (pkg, cls) -> Intent().setClassName(pkg, cls) }
        .firstOrNull { it.resolveActivityInfo(ctx.packageManager, 0)?.exported == true }
}
