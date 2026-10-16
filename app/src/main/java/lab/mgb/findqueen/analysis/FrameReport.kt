package lab.mgb.findqueen.analysis

import android.graphics.Bitmap
import androidx.core.graphics.scale
import lab.mgb.findqueen.camera.GrabbedFrame

/** What the Start button reports about one grabbed frame. */
data class FrameReport(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val captureMs: Long,
    val analysisMs: Long,
    val stats: FrameStats,
)

private const val STATS_WIDTH = 160

/** Builds the report on a downscaled copy so it stays fast on large frames. */
fun GrabbedFrame.report(): FrameReport {
    val start = System.currentTimeMillis()
    val small = bitmap.downscale(STATS_WIDTH)
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    val stats = FrameStats.compute(pixels, small.width, small.height)
    return FrameReport(
        width = bitmap.width,
        height = bitmap.height,
        rotationDegrees = rotationDegrees,
        captureMs = captureMs,
        analysisMs = System.currentTimeMillis() - start,
        stats = stats,
    )
}

private fun Bitmap.downscale(targetWidth: Int): Bitmap {
    if (width <= targetWidth) return this
    return scale(targetWidth, (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1))
}
