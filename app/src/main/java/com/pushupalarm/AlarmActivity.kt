package com.pushupalarm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

class AlarmActivity : ComponentActivity() {
    enum class Mode(val target: Int) {
        ALARM(TARGET), DISARM(TARGET), PRACTICE(TARGET), DELETE(DeleteGate.PUSHUPS)
    }

    companion object {
        const val TARGET = 15
        private const val VOLUME_STEP = 10
        private const val EXTRA_MODE = "mode"

        /** True while the ringing alarm screen is on top; the service relaunches it otherwise. */
        @Volatile var isVisible = false

        private const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_DELETE = "delete"

        fun intent(ctx: Context, mode: Mode): Intent =
            Intent(ctx, AlarmActivity::class.java).putExtra(EXTRA_MODE, mode.name)

        /** Push-ups to turn off (or, with [delete], remove) an alarm that is on. */
        fun disarmIntent(ctx: Context, alarmId: Int, delete: Boolean): Intent =
            intent(ctx, Mode.DISARM).putExtra(EXTRA_ALARM_ID, alarmId).putExtra(EXTRA_DELETE, delete)
    }

    private var mode by mutableStateOf(Mode.PRACTICE)
    private var counter = PushupCounter(TARGET)
    private var counterState by mutableStateOf(counter.state)
    private var finished by mutableStateOf(false)
    private var cameraError by mutableStateOf<String?>(null)
    private var hasCamera by mutableStateOf(false)
    private var cameraAngle by mutableStateOf(CameraAngle.SIDE)
    private var ringVolume by mutableIntStateOf(AlarmService.ringPercent)
    private var started = false
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    /** The screen follows the phone's rotation, so the camera has to as well, or frames reach the pose model sideways. */
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val rotation = display?.takeIf { it.displayId == displayId }?.rotation ?: return
            preview?.targetRotation = rotation
            analysis?.targetRotation = rotation
        }
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        switchTo(modeOf(intent))
        if (mode == Mode.ALARM && !AlarmPrefs.isRinging(this)) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this) {
            if (mode != Mode.ALARM) finish()
        }

        hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        cameraAngle = AlarmPrefs.cameraAngle(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener, null)

        setContent {
            AlarmScreen(
                mode = mode,
                state = counterState,
                finished = finished,
                hasCamera = hasCamera,
                cameraError = cameraError,
                cameraAngle = cameraAngle,
                volume = ringVolume,
                onRequestCamera = { requestPermissions(arrayOf(Manifest.permission.CAMERA), 0) },
                previewFactory = { ctx -> PreviewView(ctx).also(::startCamera) },
                onSwitchAngle = ::switchAngle,
                onVolume = ::adjustVolume,
                onEmergency = ::emergencyCall,
                onCancel = ::finish,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A real alarm takes over a practice or disarm session.
        if (modeOf(intent) == Mode.ALARM && AlarmPrefs.isRinging(this) && mode != Mode.ALARM) {
            switchTo(Mode.ALARM)
            isVisible = started
        }
    }

    /** Starts a fresh count, so reps from practice/disarm never carry into the alarm. */
    private fun switchTo(newMode: Mode) {
        mode = newMode
        counter = PushupCounter(newMode.target)
        counterState = counter.state
        finished = false
    }

    override fun onStart() {
        super.onStart()
        started = true
        isVisible = mode == Mode.ALARM
        ringVolume = AlarmService.ringPercent
    }

    override fun onStop() {
        started = false
        isVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationListener)
        detector.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (mode != Mode.ALARM || !volumeKey) return super.dispatchKeyEvent(event)
        // While ringing, the volume keys move this ring's level but can't take it below the floor or mute it.
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(VOLUME_STEP)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(-VOLUME_STEP)
        }
        return true
    }

    private fun adjustVolume(deltaPercent: Int) {
        ringVolume = AlarmService.adjustVolume(this, deltaPercent)
    }

    private fun switchAngle() {
        cameraAngle = if (cameraAngle == CameraAngle.SIDE) CameraAngle.FRONT else CameraAngle.SIDE
        AlarmPrefs.setCameraAngle(this, cameraAngle)
    }

    private fun modeOf(intent: Intent?) =
        intent?.getStringExtra(EXTRA_MODE)?.let { Mode.valueOf(it) } ?: Mode.PRACTICE

    private fun startCamera(previewView: PreviewView) {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER // show the whole frame the pose model sees
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val rotation = display?.rotation ?: Surface.ROTATION_90
                val preview = Preview.Builder().setTargetRotation(rotation).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                this.preview = preview
                this.analysis = analysis
                cameraError = null
            } catch (e: Exception) {
                cameraError = e.message ?: "Camera unavailable"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { pose ->
                if (!finished) {
                    counterState = counter.update(PoseReading.read(pose, cameraAngle), SystemClock.elapsedRealtime())
                    if (counterState.cue == Cue.DONE) complete()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun complete() {
        finished = true
        when (mode) {
            Mode.ALARM -> {
                AlarmPrefs.setRinging(this, false)
                stopService(Intent(this, AlarmService::class.java))
            }
            Mode.DISARM -> {
                val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                AlarmScheduler.cancel(this, id)
                if (intent.getBooleanExtra(EXTRA_DELETE, false)) {
                    AlarmStore.delete(this, id)
                } else {
                    AlarmStore.get(this, id)?.let { AlarmStore.save(this, it.copy(enabled = false)) }
                }
            }
            Mode.DELETE -> DeleteGate.startCooldown(this)
            Mode.PRACTICE -> Unit
        }
        window.decorView.postDelayed({ finish() }, 2500)
    }

    private fun emergencyCall() {
        AlarmService.emergencyUntil = System.currentTimeMillis() + 30_000
        try {
            startActivity(Intent("com.android.phone.EmergencyDialer.DIAL").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
