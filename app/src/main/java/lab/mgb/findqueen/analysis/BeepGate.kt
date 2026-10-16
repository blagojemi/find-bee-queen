package lab.mgb.findqueen.analysis

import lab.mgb.findqueen.analysis.BeeVerdict.Outcome

/**
 * Decides when live mode beeps: once when bees are first found, not on every frame.
 * A new beep needs the result to leave BEES and come back, and at least [cooldownMs]
 * since the last beep, so a flickering result does not beep constantly.
 * Pure Kotlin so it can be unit-tested without Android.
 */
class BeepGate(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private var lastOutcome: Outcome? = null
    private var lastBeepAt: Long? = null

    /** Feed every live result; returns true when a beep should play now. */
    fun onResult(outcome: Outcome?, nowMs: Long): Boolean {
        val enteredBees = outcome == Outcome.BEES && lastOutcome != Outcome.BEES
        lastOutcome = outcome
        val last = lastBeepAt
        if (!enteredBees || (last != null && nowMs - last < cooldownMs)) return false
        lastBeepAt = nowMs
        return true
    }

    /** Forget history, e.g. when the user presses Stop. */
    fun reset() {
        lastOutcome = null
        lastBeepAt = null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2_000L
    }
}
