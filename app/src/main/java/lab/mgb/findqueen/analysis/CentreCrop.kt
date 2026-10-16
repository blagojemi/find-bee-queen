package lab.mgb.findqueen.analysis

/**
 * The centre part of a frame that the bee check also scores, as if the camera were closer.
 * Helps when the comb fills only part of the view (frame held further away, hive edges,
 * hands around it). Pure Kotlin so it can be unit-tested without Android.
 */
data class CentreCrop(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        /** Keeps [fraction] of each side, centred; never smaller than 1×1. */
        fun of(width: Int, height: Int, fraction: Float): CentreCrop {
            val w = (width * fraction).toInt().coerceIn(1, width)
            val h = (height * fraction).toInt().coerceIn(1, height)
            return CentreCrop((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
