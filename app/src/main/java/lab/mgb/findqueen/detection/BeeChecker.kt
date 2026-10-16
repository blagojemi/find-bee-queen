package lab.mgb.findqueen.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.scale
import lab.mgb.findqueen.analysis.BeeVerdict
import lab.mgb.findqueen.analysis.CentreCrop
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.Closeable

/**
 * Result of one bee check: the verdict uses the higher of the whole-frame and centre-crop
 * scores; both are kept for the debug log.
 */
data class BeeCheckResult(
    val verdict: BeeVerdict,
    val inferenceMs: Long,
    val fullScore: Float,
    val cropScore: Float,
)

/**
 * Fast whole-frame classifier: "are there honey bees (Apis mellifera carnica) on this
 * comb frame?". Expects a small TFLite image classifier with metadata in assets/[MODEL_FILE]
 * whose labels include [BeeVerdict.BEES_LABEL] (e.g. `bees` / `no_bees`).
 *
 * Each frame is scored twice: whole, and the centre [CROP_FRACTION] of it, and the higher
 * score wins. The crop helps when the comb fills only part of the view; phone test
 * 2026-10-15: comb at a distance went from 1/36 to 16/36 green.
 *
 * Kept cheap on purpose: load the model once, shrink the frame before inference and use few
 * threads.
 */
class BeeChecker private constructor(
    private val classifier: ImageClassifier,
) : Closeable {

    fun check(bitmap: Bitmap): BeeCheckResult {
        val start = SystemClock.uptimeMillis()
        val small = bitmap.shrink()
        val full = beeScore(small)
        val c = CentreCrop.of(small.width, small.height, CROP_FRACTION)
        val crop = beeScore(Bitmap.createBitmap(small, c.x, c.y, c.width, c.height))
        return BeeCheckResult(
            verdict = BeeVerdict.fromScore(maxOf(full, crop)),
            inferenceMs = SystemClock.uptimeMillis() - start,
            fullScore = full,
            cropScore = crop,
        )
    }

    private fun beeScore(image: Bitmap): Float {
        val scores = classifier.classify(TensorImage.fromBitmap(image))
            .firstOrNull()?.categories.orEmpty()
            .associate { it.label to it.score }
        return BeeVerdict.from(scores).beeScore
    }

    override fun close() = classifier.close()

    /** The model resizes to its own input (e.g. 224 px) anyway; shrinking first saves work. */
    private fun Bitmap.shrink(): Bitmap {
        val longSide = maxOf(width, height)
        if (longSide <= MAX_INPUT_SIDE) return this
        val ratio = MAX_INPUT_SIDE.toFloat() / longSide
        return scale((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
    }

    companion object {
        const val MODEL_FILE = "bee_check.tflite"
        const val CROP_FRACTION = 0.6f
        private const val MAX_INPUT_SIDE = 448
        private const val THREADS = 2

        /** Returns null if the model asset is missing or cannot be loaded. */
        fun create(context: Context): BeeChecker? {
            val options = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(THREADS).build())
                .build()
            return runCatching {
                ImageClassifier.createFromFileAndOptions(context, MODEL_FILE, options)
            }.map { BeeChecker(it) }.getOrNull()
        }
    }
}
