package lab.mgb.findqueen.detection

import android.graphics.RectF

/** A single detection in upright image pixel coordinates. */
data class Detection(
    val label: String,
    val score: Float,
    val box: RectF,
)

/** Result of analysing one camera frame. */
data class FrameResult(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceMs: Long,
)
