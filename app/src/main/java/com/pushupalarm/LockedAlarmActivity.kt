package com.pushupalarm

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shown while the alarm rings before the first unlock after a restart. The camera and pose detector
 * can't start until then, so this asks for the unlock and [AlarmService] opens the push-up screen after it.
 * Plain views on purpose: Compose's start-up code doesn't run before the first unlock.
 */
class LockedAlarmActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val closeWhenUnlocked = object : Runnable {
        override fun run() {
            if (DeviceStorage.isUnlocked(this@LockedAlarmActivity) || !AlarmPrefs.isRinging(this@LockedAlarmActivity)) {
                finish()
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val gap = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(gap, gap, gap, gap)
                addView(text("Wake up!", 44f))
                addView(
                    text("Your phone restarted. Unlock it, then do ${AlarmActivity.TARGET} push-ups to stop the alarm.", 20f)
                        .apply { setPadding(0, gap, 0, gap) },
                )
                addView(
                    Button(this@LockedAlarmActivity).apply {
                        setText("Unlock")
                        setOnClickListener {
                            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this@LockedAlarmActivity, null)
                        }
                    },
                )
            },
        )
    }

    private fun text(value: String, sizeSp: Float) = TextView(this).apply {
        text = value
        textSize = sizeSp
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
    }

    override fun onStart() {
        super.onStart()
        handler.post(closeWhenUnlocked)
    }

    override fun onStop() {
        handler.removeCallbacks(closeWhenUnlocked)
        super.onStop()
    }
}
