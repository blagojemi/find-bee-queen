package lab.mgb.findqueen.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class CentreCropTest {
    @Test
    fun `sixty percent of a phone frame is centred`() {
        assertEquals(CentreCrop(x = 96, y = 128, width = 288, height = 384), CentreCrop.of(480, 640, 0.6f))
    }

    @Test
    fun `full fraction keeps the whole frame`() {
        assertEquals(CentreCrop(0, 0, 336, 448), CentreCrop.of(336, 448, 1f))
    }

    @Test
    fun `tiny frames never get an empty crop`() {
        assertEquals(CentreCrop(0, 0, 1, 1), CentreCrop.of(1, 1, 0.6f))
    }
}
