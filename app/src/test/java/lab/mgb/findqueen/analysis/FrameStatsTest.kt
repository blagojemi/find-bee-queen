package lab.mgb.findqueen.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `flat gray frame has its brightness and no sharpness`() {
        val stats = FrameStats.compute(IntArray(10 * 10) { gray(128) }, 10, 10)
        assertEquals(128.0, stats.brightness, 0.01)
        assertEquals(0.0, stats.sharpness, 0.01)
        assertTrue(stats.isBlurry)
    }

    @Test
    fun `black frame is too dark`() {
        assertTrue(FrameStats.compute(IntArray(16) { gray(0) }, 4, 4).isTooDark)
    }

    @Test
    fun `checkerboard is sharp`() {
        val w = 20
        val pixels = IntArray(w * w) { i -> if ((i % w + i / w) % 2 == 0) gray(0) else gray(255) }
        val stats = FrameStats.compute(pixels, w, w)
        assertTrue(stats.sharpness > FrameStats.BLUR_LIMIT)
    }
}
