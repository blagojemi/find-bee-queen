package lab.mgb.findqueen.analysis

/**
 * Cheap image-quality numbers for a grabbed frame, computed on a small grayscale copy.
 * Pure Kotlin so it can be unit-tested without Android.
 */
data class FrameStats(
    /** Mean luma, 0 (black) to 255 (white). */
    val brightness: Double,
    /** Variance of the Laplacian; low values mean a blurry frame. */
    val sharpness: Double,
) {
    val isTooDark get() = brightness < DARK_LIMIT
    val isTooBright get() = brightness > BRIGHT_LIMIT
    val isBlurry get() = sharpness < BLUR_LIMIT

    /** Why the frame is not worth analysing, or null if it is usable. */
    val problem: String?
        get() = when {
            isTooDark -> "too dark"
            isTooBright -> "too bright"
            isBlurry -> "blurry"
            else -> null
        }

    companion object {
        const val DARK_LIMIT = 40.0
        const val BRIGHT_LIMIT = 220.0
        // Rough starting point for a ~160 px wide frame; tune on real hive photos.
        const val BLUR_LIMIT = 50.0

        /** [pixels] are ARGB ints, row-major, [width] x [height]. */
        fun compute(pixels: IntArray, width: Int, height: Int): FrameStats {
            require(pixels.size == width * height) { "pixels do not match $width x $height" }
            val luma = DoubleArray(pixels.size) { i ->
                val p = pixels[i]
                0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
            }
            return FrameStats(brightness = luma.average(), sharpness = laplacianVariance(luma, width, height))
        }

        private fun laplacianVariance(luma: DoubleArray, width: Int, height: Int): Double {
            if (width < 3 || height < 3) return 0.0
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val lap = luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width] - 4 * luma[i]
                    sum += lap
                    sumSq += lap * lap
                    n++
                }
            }
            val mean = sum / n
            return sumSq / n - mean * mean
        }
    }
}
