package lab.mgb.findqueen.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import lab.mgb.findqueen.analysis.FrameReport
import lab.mgb.findqueen.analysis.report
import lab.mgb.findqueen.debug.DebugRecord
import lab.mgb.findqueen.debug.DebugSession
import lab.mgb.findqueen.detection.BeeCheckResult
import lab.mgb.findqueen.detection.BeeChecker

/** Result of checking one live frame. [bees] is null if the frame was unusable or there is no model. */
class LiveResult(
    val report: FrameReport,
    val bees: BeeCheckResult?,
    /** Frames checked per second, measured between consecutive results. */
    val fps: Float,
)

/**
 * Live mode: runs the frame check and the bee check on camera frames as fast as the phone
 * can. ImageAnalysis keeps only the latest frame, so slow phones skip frames instead of
 * queueing them. Bound only while the user has pressed Start. If [debugSession] is set,
 * every result is also written to the debug log (with a thumbnail about once a second).
 */
class BeeAnalyzer(
    private val checker: BeeChecker?,
    private val onResult: (LiveResult) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastResultAt = 0L

    /** Set on Start, cleared on Stop; written from the camera thread. */
    @Volatile
    var debugSession: DebugSession? = null

    override fun analyze(image: ImageProxy) {
        val frame = image.use {
            val rotation = it.imageInfo.rotationDegrees
            GrabbedFrame(it.toBitmap().rotate(rotation), rotation, captureMs = 0)
        }
        val report = frame.report()
        // The checker may already be closed if the screen is leaving; treat that as "no result".
        val bees = if (report.stats.problem == null) runCatching { checker?.check(frame.bitmap) }.getOrNull() else null

        val now = SystemClock.uptimeMillis()
        val fps = if (lastResultAt == 0L) 0f else 1000f / (now - lastResultAt).coerceAtLeast(1)
        lastResultAt = now
        val result = LiveResult(report, bees, fps)
        debugSession?.let { log(it, result, frame) }
        onResult(result)
    }

    private fun log(session: DebugSession, result: LiveResult, frame: GrabbedFrame) {
        val stats = result.report.stats
        val bees = result.bees
        session.frame(
            DebugRecord(
                tMs = session.elapsedMs(),
                fps = result.fps,
                width = result.report.width,
                height = result.report.height,
                rotation = result.report.rotationDegrees,
                brightness = stats.brightness,
                sharpness = stats.sharpness,
                problem = stats.problem,
                beeScore = bees?.verdict?.beeScore,
                outcome = when {
                    stats.problem != null -> "SKIPPED"
                    bees == null -> "NO_MODEL"
                    else -> bees.verdict.outcome.name
                },
                statsMs = result.report.analysisMs,
                beeMs = bees?.inferenceMs,
                thumb = session.maybeThumb(frame.bitmap),
                fullScore = bees?.fullScore,
                cropScore = bees?.cropScore,
            ),
        )
    }
}
