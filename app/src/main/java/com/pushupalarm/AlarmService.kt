package com.pushupalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat

/**
 * Runs for as long as the alarm is ringing. Every [TICK_MS] it re-pins the alarm volume and
 * drags the push-up screen back to the front if you left it. It only stops itself once
 * [AlarmPrefs.isRinging] is cleared, which only happens after the 15th rep.
 */
class AlarmService : Service() {
    companion object {
        private const val CHANNEL = "alarm"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 500L

        /** Set when the emergency-call button is tapped, so the dialer isn't covered immediately. */
        @Volatile var emergencyUntil = 0L

        /** This ring's volume. Starts at the saved level; can be nudged while ringing, never below the floor. */
        @Volatile var ringPercent = 100
            private set

        /** Changes only this ring's volume, not the saved setting. Returns the new level. */
        fun adjustVolume(ctx: Context, deltaPercent: Int): Int {
            ringPercent = (ringPercent + deltaPercent).coerceIn(AlarmSound.MIN_PERCENT, 100)
            ctx.getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, AlarmSound.index(ctx, ringPercent), 0)
            return ringPercent
        }

        /** True from the first start until the service is destroyed. */
        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, AlarmService::class.java))
            } catch (_: IllegalStateException) {
                // Android 12+ refuses when the trigger wasn't an exact alarm (e.g. the inexact fallback in
                // AlarmScheduler). Ring via the full-screen notification instead; AlarmActivity starts the service.
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(ctx))
            }
        }

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val open = PendingIntent.getActivity(
                ctx, 0,
                AlarmActivity.intent(ctx, AlarmActivity.Mode.ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("Wake up!")
                .setContentText("Do ${AlarmActivity.TARGET} push-ups to stop the alarm")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .build()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var vibrator: Vibrator
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var vibrating = false
    private var originalVolume = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!AlarmPrefs.isRinging(this@AlarmService)) {
                stopSelf()
                return
            }
            enforce()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AlarmPrefs.isRinging(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            // Not allowed to start from here (e.g. a sticky restart); come back via an alarm-clock broadcast.
            AlarmScheduler.scheduleResume(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            isRunning = true
            ringPercent = AlarmPrefs.volumePercent(this)
            audio = getSystemService(AudioManager::class.java)
            originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PushupAlarm:ringing")
                .apply { acquire(2 * 60 * 60 * 1000L) }
            startSound()
            launchAlarmScreen()
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.run { stop(); release() }
        player = null
        if (running) {
            vibrator.cancel()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        isRunning = false
        // Killed while still ringing: resurrect.
        if (AlarmPrefs.isRinging(this)) AlarmScheduler.scheduleResume(this)
        super.onDestroy()
    }

    private fun enforce() {
        // Step aside for phone calls (including emergency calls).
        val inCall = audio.mode in setOf(
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE,
        )
        if (inCall) {
            player?.takeIf { it.isPlaying }?.pause()
            if (vibrating) vibrator.cancel()
            vibrating = false
            return
        }

        audio.setStreamVolume(AudioManager.STREAM_ALARM, AlarmSound.index(this, ringPercent), 0)
        player?.takeIf { !it.isPlaying }?.start()
        if (!vibrating) startVibration()
        if (!AlarmActivity.isVisible && System.currentTimeMillis() > emergencyUntil) launchAlarmScreen()
    }

    private fun launchAlarmScreen() {
        try {
            startActivity(AlarmActivity.intent(this, AlarmActivity.Mode.ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Background launch blocked; the full-screen notification is the fallback.
        }
    }

    private fun startSound() {
        val uri = AlarmSound.uri(this) ?: return
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startVibration() {
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            // Alarm usage is what lets it vibrate through Do Not Disturb before VibrationAttributes existed.
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        vibrating = true
    }
}
