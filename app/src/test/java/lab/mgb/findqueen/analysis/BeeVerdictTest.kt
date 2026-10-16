package lab.mgb.findqueen.analysis

import lab.mgb.findqueen.analysis.BeeVerdict.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class BeeVerdictTest {
    @Test
    fun `high bee score means bees`() {
        val verdict = BeeVerdict.from(mapOf("bees" to 0.9f, "no_bees" to 0.1f))
        assertEquals(Outcome.BEES, verdict.outcome)
        assertEquals(0.9f, verdict.beeScore, 0.001f)
    }

    @Test
    fun `low bee score means no bees`() {
        assertEquals(Outcome.NO_BEES, BeeVerdict.from(mapOf("bees" to 0.1f, "no_bees" to 0.9f)).outcome)
    }

    @Test
    fun `middle band is unsure`() {
        assertEquals(Outcome.UNSURE, BeeVerdict.from(mapOf("bees" to 0.5f, "no_bees" to 0.5f)).outcome)
    }

    @Test
    fun `verdict from a known score uses the same bands`() {
        assertEquals(Outcome.BEES, BeeVerdict.fromScore(0.7f).outcome)
        assertEquals(Outcome.UNSURE, BeeVerdict.fromScore(0.5f).outcome)
        assertEquals(Outcome.NO_BEES, BeeVerdict.fromScore(0.2f).outcome)
    }

    @Test
    fun `label match ignores case`() {
        assertEquals(Outcome.BEES, BeeVerdict.from(mapOf("Bees" to 0.8f)).outcome)
    }

    @Test
    fun `missing bees label means no bees`() {
        val verdict = BeeVerdict.from(mapOf("no_bees" to 0.99f))
        assertEquals(Outcome.NO_BEES, verdict.outcome)
        assertEquals(0f, verdict.beeScore, 0.001f)
    }
}
