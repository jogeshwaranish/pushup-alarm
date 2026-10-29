package com.pushupalarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.math.ceil

object AlarmSound {
    /** Floor on the volume setting, so it can be quieter but never silent. */
    const val MIN_PERCENT = 20

    fun uri(ctx: Context): Uri? = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** The alarm-stream volume index for [percent], by default the user's chosen level. */
    fun index(ctx: Context, percent: Int = AlarmPrefs.volumePercent(ctx)): Int {
        val max = ctx.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_ALARM)
        return ceil(max * percent.coerceIn(MIN_PERCENT, 100) / 100.0).toInt().coerceIn(1, max)
    }

    /** Plays two seconds of the alarm at the chosen level, then restores the previous volume. */
    fun preview(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val original = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val tone = uri(ctx)?.let { RingtoneManager.getRingtone(ctx, it) } ?: return
        audio.setStreamVolume(AudioManager.STREAM_ALARM, index(ctx), 0)
        tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        tone.play()
        Handler(Looper.getMainLooper()).postDelayed({
            tone.stop()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
        }, 2000)
    }
}
