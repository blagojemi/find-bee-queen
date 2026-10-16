package lab.mgb.findqueen.analysis

/**
 * Outcome of the quick "are there honey bees on this frame?" check.
 * Pure Kotlin so the decision rule can be unit-tested without Android.
 */
data class BeeVerdict(
    val outcome: Outcome,
    /** Model score for the [BEES_LABEL] class, 0..1. */
    val beeScore: Float,
) {
    /** UNSURE is a deliberate middle band: better to ask for another frame than to guess. */
    enum class Outcome { BEES, NO_BEES, UNSURE }

    companion object {
        /** Class name the bee-check model must use for "honey bees on the comb". */
        const val BEES_LABEL = "bees"
        const val BEES_ABOVE = 0.65f
        const val NO_BEES_BELOW = 0.35f

        /** [scores] maps model labels to scores; any label other than [BEES_LABEL] counts as "no bees". */
        fun from(
            scores: Map<String, Float>,
            beesAbove: Float = BEES_ABOVE,
            noBeesBelow: Float = NO_BEES_BELOW,
        ): BeeVerdict {
            val beeScore = scores.entries
                .firstOrNull { it.key.equals(BEES_LABEL, ignoreCase = true) }
                ?.value ?: 0f
            return fromScore(beeScore, beesAbove, noBeesBelow)
        }

        /** Verdict for a bee score (0..1) that is already known. */
        fun fromScore(
            beeScore: Float,
            beesAbove: Float = BEES_ABOVE,
            noBeesBelow: Float = NO_BEES_BELOW,
        ): BeeVerdict {
            val outcome = when {
                beeScore >= beesAbove -> Outcome.BEES
                beeScore < noBeesBelow -> Outcome.NO_BEES
                else -> Outcome.UNSURE
            }
            return BeeVerdict(outcome, beeScore)
        }
    }
}
