package lab.mgb.findqueen.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import lab.mgb.findqueen.detection.FrameResult
import lab.mgb.findqueen.detection.QueenDetector

/** CameraX analyzer that feeds upright frames into [QueenDetector]. */
class QueenAnalyzer(
    private val detector: QueenDetector,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        image.use {
            val upright = it.toBitmap().rotate(it.imageInfo.rotationDegrees)
            onResult(detector.detect(upright))
        }
    }
}
