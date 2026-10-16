package lab.mgb.findqueen.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.Closeable

/**
 * Wraps a TFLite object-detection model trained to find queen bees on hive frames.
 * The model must ship with TFLite metadata (labels, normalization) in assets/[MODEL_FILE].
 */
class QueenDetector private constructor(
    private val detector: ObjectDetector,
    private val queenLabel: String,
) : Closeable {

    /** Runs detection on an upright bitmap and returns only queen detections. */
    fun detect(bitmap: Bitmap): FrameResult {
        val start = SystemClock.uptimeMillis()
        val results = detector.detect(TensorImage.fromBitmap(bitmap))
        val detections = results.mapNotNull { result ->
            val category = result.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            if (!category.label.equals(queenLabel, ignoreCase = true)) return@mapNotNull null
            Detection(category.label, category.score, result.boundingBox)
        }
        return FrameResult(
            detections = detections,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            inferenceMs = SystemClock.uptimeMillis() - start,
        )
    }

    override fun close() = detector.close()

    companion object {
        const val MODEL_FILE = "queen_detector.tflite"

        /** Returns null if the model asset is missing or cannot be loaded. */
        fun create(
            context: Context,
            scoreThreshold: Float = 0.5f,
            maxResults: Int = 5,
            queenLabel: String = "queen",
        ): QueenDetector? {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(maxResults)
                .build()
            return runCatching {
                ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            }.map { QueenDetector(it, queenLabel) }.getOrNull()
        }
    }
}
