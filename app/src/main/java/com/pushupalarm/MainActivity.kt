package com.pushupalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so setup checks and armed state refresh after Settings or a disarm. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MainScreen(resumeTick) }
    }

    override fun onResume() {
        super.onResume()
        if (AlarmPrefs.isRinging(this)) startActivity(AlarmActivity.intent(this, AlarmActivity.Mode.ALARM))
        resumeTick++
    }
}
