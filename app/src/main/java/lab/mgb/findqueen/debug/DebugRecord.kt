package lab.mgb.findqueen.debug

import java.util.Locale

/**
 * One line of the debug log's frames.csv: everything the live check decided about one frame.
 * Pure Kotlin so the format can be unit-tested without Android.
 */
data class DebugRecord(
    /** Milliseconds since Start was pressed. */
    val tMs: Long,
    val fps: Float,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val brightness: Double,
    val sharpness: Double,
    /** "too dark" / "too bright" / "blurry", or null if the frame was usable. */
    val problem: String?,
    /** Bee score 0..1, or null if the bee check did not run. */
    val beeScore: Float?,
    /** BEES / NO_BEES / UNSURE, SKIPPED (bad frame) or NO_MODEL. */
    val outcome: String,
    val statsMs: Long,
    val beeMs: Long?,
    /** Thumbnail file saved for this frame, if any. */
    val thumb: String?,
    /** Bee score of the whole frame and of its centre crop; [beeScore] is the higher one. */
    val fullScore: Float? = null,
    val cropScore: Float? = null,
) {
    fun toCsvLine(): String = listOf(
        tMs.toString(),
        "%.1f".format(Locale.US, fps),
        width.toString(),
        height.toString(),
        rotation.toString(),
        "%.1f".format(Locale.US, brightness),
        "%.1f".format(Locale.US, sharpness),
        problem.orEmpty(),
        beeScore?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        outcome,
        statsMs.toString(),
        beeMs?.toString().orEmpty(),
        thumb.orEmpty(),
        fullScore?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        cropScore?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER =
            "t_ms,fps,width,height,rotation,brightness,sharpness,problem,bee_score,outcome,stats_ms,bee_ms,thumb," +
                "full_score,crop_score"
    }
}
