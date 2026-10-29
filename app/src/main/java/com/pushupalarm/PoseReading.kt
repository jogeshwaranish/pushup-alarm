package com.pushupalarm

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/** Where the phone sits relative to the person doing push-ups. */
enum class CameraAngle { SIDE, FRONT }

object PoseReading {
    fun read(pose: Pose, angle: CameraAngle): Frame? = when (angle) {
        CameraAngle.SIDE -> bestSide(pose)
        CameraAngle.FRONT -> front(pose)
    }

    /** Picks whichever body side the camera sees best (the one facing it, in a side view). */
    fun bestSide(pose: Pose): Side? = listOfNotNull(
        side(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_HIP),
        side(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_HIP),
    ).maxByOrNull { it.confidence }

    private fun side(pose: Pose, vararg types: Int): Side? {
        val marks = types.map { pose.getPoseLandmark(it) ?: return null }
        val pts = marks.map { P(it.position.x, it.position.y) }
        return Side(pts[0], pts[1], pts[2], pts[3], marks.minOf { it.inFrameLikelihood })
    }

    /** Shoulders and wrists must be seen; hips only count when clearly in view. */
    fun front(pose: Pose): Front? {
        val marks = listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        ).map { pose.getPoseLandmark(it) ?: return null }
        val pts = marks.map { P(it.position.x, it.position.y) }
        val l = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val r = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val hips = if (l != null && r != null && minOf(l.inFrameLikelihood, r.inFrameLikelihood) >= 0.5f) {
            P((l.position.x + r.position.x) / 2, (l.position.y + r.position.y) / 2)
        } else null
        return Front(pts[0], pts[1], pts[2], pts[3], hips, marks.minOf { it.inFrameLikelihood })
    }
}
