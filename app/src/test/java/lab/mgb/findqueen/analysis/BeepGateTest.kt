package lab.mgb.findqueen.analysis

import lab.mgb.findqueen.analysis.BeeVerdict.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepGateTest {
    @Test
    fun `beeps once when bees are first found`() {
        val gate = BeepGate(cooldownMs = 2_000)
        assertFalse(gate.onResult(Outcome.NO_BEES, 0))
        assertTrue(gate.onResult(Outcome.BEES, 100))
        assertFalse(gate.onResult(Outcome.BEES, 150))
        assertFalse(gate.onResult(Outcome.BEES, 5_000))
    }

    @Test
    fun `flicker inside the cooldown does not beep again`() {
        val gate = BeepGate(cooldownMs = 2_000)
        assertTrue(gate.onResult(Outcome.BEES, 0))
        assertFalse(gate.onResult(Outcome.UNSURE, 500))
        assertFalse(gate.onResult(Outcome.BEES, 600))
    }

    @Test
    fun `bees found again after the cooldown beeps again`() {
        val gate = BeepGate(cooldownMs = 2_000)
        assertTrue(gate.onResult(Outcome.BEES, 0))
        assertFalse(gate.onResult(Outcome.NO_BEES, 1_000))
        assertTrue(gate.onResult(Outcome.BEES, 3_000))
    }

    @Test
    fun `unusable frames count as leaving bees`() {
        val gate = BeepGate(cooldownMs = 0)
        assertTrue(gate.onResult(Outcome.BEES, 0))
        assertFalse(gate.onResult(null, 10))
        assertTrue(gate.onResult(Outcome.BEES, 20))
    }

    @Test
    fun `reset starts fresh`() {
        val gate = BeepGate(cooldownMs = 10_000)
        assertTrue(gate.onResult(Outcome.BEES, 0))
        gate.reset()
        assertTrue(gate.onResult(Outcome.BEES, 100))
    }
}
