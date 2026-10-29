package com.pushupalarm

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** A 2D point in upright image coordinates (y grows downward). */
data class P(val x: Float, val y: Float)

/** What the camera sees in one frame. [confidence] is the weakest landmark's in-frame likelihood. */
sealed interface Frame {
    val confidence: Float
}

/** One side of the body, with the camera off to the side. */
data class Side(val shoulder: P, val elbow: P, val wrist: P, val hip: P, override val confidence: Float) : Frame

/**
 * Both arms, with the camera on the floor in front of the head. [hips] is the point between
 * the hips, or null when they're hidden behind the shoulders (as they usually are in a plank).
 */
data class Front(
    val leftShoulder: P,
    val rightShoulder: P,
    val leftWrist: P,
    val rightWrist: P,
    val hips: P?,
    override val confidence: Float,
) : Frame

enum class Cue { FIND_BODY, NOT_HORIZONTAL, GET_IN_PLANK, GO_DOWN, PUSH_UP, DONE }

/** [arm] is how straight the arms are: elbow degrees from the side, or % of locked-out from the front. */
data class CounterState(val reps: Int, val target: Int, val cue: Cue, val arm: Int?)

/**
 * Counts strict push-ups. A rep only counts when the body is in a plank with hands below
 * shoulders, the arms bend past the down threshold, and then lock back out past the up one.
 * Losing tracking for [lostResetMs] cancels a half-done rep, so you have to re-establish a
 * plank before the next one counts.
 *
 * From the side the arm is the elbow angle ([downAngle], [upAngle]). From the front the elbow
 * angle can't be seen, so the arm is how far the shoulders sit above the wrists, as a % of the
 * locked-out height measured earlier in the session ([frontDown], [frontUp]).
 */
class PushupCounter(
    val target: Int = 15,
    private val downAngle: Double = 100.0,
    private val upAngle: Double = 150.0,
    private val maxTorsoTilt: Double = 40.0,
    private val frontDown: Double = 70.0,
    private val frontUp: Double = 90.0,
    private val maxFrontTorso: Double = 0.9,
    private val minConfidence: Float = 0.5f,
    private val minRepMs: Long = 600,
    private val lostResetMs: Long = 1500,
    private val streakFrames: Int = 2,
) {
    private enum class Phase { NONE, UP, DOWN }

    private var phase = Phase.NONE
    private var reps = 0
    private var smoothed: Double? = null
    private var streak = 0
    private var lastValidMs = Long.MIN_VALUE
    private var lastRepMs = Long.MIN_VALUE / 2 // halved so timeMs - lastRepMs can't overflow
    private var front = false // which view the current phase and smoothing belong to

    /** Locked-out shoulder-to-wrist drop from the front, in shoulder widths. */
    private var reach = 0.0
    private val recentDrops = ArrayDeque<Double>()

    var state = CounterState(0, target, Cue.FIND_BODY, null)
        private set

    fun update(frame: Frame?, timeMs: Long): CounterState {
        if (reps >= target) return emit(Cue.DONE)

        if (frame == null || frame.confidence < minConfidence) return invalid(Cue.FIND_BODY, timeMs)
        if ((frame is Front) != front) {
            front = frame is Front
            resetRep()
        }
        val raw = when (frame) {
            is Side -> sideArm(frame)
            is Front -> frontArm(frame)
        } ?: return invalid(Cue.NOT_HORIZONTAL, timeMs)
        lastValidMs = timeMs

        val arm = smoothed?.let { it * 0.5 + raw * 0.5 } ?: raw
        smoothed = arm
        val down = if (front) frontDown else downAngle
        val up = if (front) frontUp else upAngle

        val cue = when (phase) {
            Phase.NONE -> if (holds(arm > up)) {
                phase = Phase.UP
                Cue.GO_DOWN
            } else Cue.GET_IN_PLANK

            Phase.UP -> if (holds(arm < down)) {
                phase = Phase.DOWN
                Cue.PUSH_UP
            } else Cue.GO_DOWN

            Phase.DOWN -> if (holds(arm > up) && timeMs - lastRepMs >= minRepMs) {
                phase = Phase.UP
                reps++
                lastRepMs = timeMs
                if (reps >= target) Cue.DONE else Cue.GO_DOWN
            } else Cue.PUSH_UP
        }
        return emit(cue, arm.toInt())
    }

    /** Elbow angle, or null if the body isn't horizontal with hands below shoulders. */
    private fun sideArm(s: Side): Double? =
        if (torsoTilt(s) > maxTorsoTilt || s.wrist.y < s.shoulder.y) null else elbowAngle(s)

    /** % of locked-out shoulder height, or null if upright (standing curls) or hands are above shoulders. */
    private fun frontArm(f: Front): Double? {
        val width = hypot(f.rightShoulder.x - f.leftShoulder.x, f.rightShoulder.y - f.leftShoulder.y).toDouble()
        if (width == 0.0) return null
        val shoulderY = (f.leftShoulder.y + f.rightShoulder.y) / 2.0
        if (f.hips != null && (f.hips.y - shoulderY) / width > maxFrontTorso) return null
        val drop = ((f.leftWrist.y + f.rightWrist.y) / 2.0 - shoulderY) / width
        if (drop <= 0) return null

        // Calibrate on the lowest of the last 3 frames, so one glitchy frame can't set a lockout you can't reach.
        recentDrops.addLast(drop)
        if (recentDrops.size > 3) recentDrops.removeFirst()
        reach = max(reach, recentDrops.min())
        return (drop / reach * 100).coerceAtMost(100.0)
    }

    /** Requires a condition to hold for [streakFrames] consecutive frames, filtering single-frame jitter. */
    private fun holds(condition: Boolean): Boolean {
        streak = if (condition) streak + 1 else 0
        if (streak >= streakFrames) {
            streak = 0
            return true
        }
        return false
    }

    private fun invalid(cue: Cue, timeMs: Long): CounterState {
        streak = 0
        if (lastValidMs == Long.MIN_VALUE || timeMs - lastValidMs > lostResetMs) resetRep()
        return emit(cue)
    }

    /** Drops any half-done rep and, from the front, the lockout calibration (the person may have moved). */
    private fun resetRep() {
        phase = Phase.NONE
        smoothed = null
        streak = 0
        reach = 0.0
        recentDrops.clear()
    }

    private fun emit(cue: Cue, angle: Int? = null): CounterState {
        state = CounterState(reps, target, cue, angle)
        return state
    }

    companion object {
        fun elbowAngle(s: Side): Double {
            val ax = s.shoulder.x - s.elbow.x
            val ay = s.shoulder.y - s.elbow.y
            val bx = s.wrist.x - s.elbow.x
            val by = s.wrist.y - s.elbow.y
            val denom = hypot(ax, ay) * hypot(bx, by)
            if (denom == 0f) return 180.0
            val cos = ((ax * bx + ay * by) / denom).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos.toDouble()))
        }

        /** Angle of the shoulder→hip line from horizontal, 0..90 degrees. */
        fun torsoTilt(s: Side): Double {
            val dx = abs(s.hip.x - s.shoulder.x)
            val dy = abs(s.hip.y - s.shoulder.y)
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        }
    }
}
