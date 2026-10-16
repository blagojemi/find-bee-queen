package lab.mgb.findqueen.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMapperTest {
    @Test
    fun `fill center crops the wider dimension`() {
        // 400x300 image into a 300x300 view: scale = 1.0, 50px cropped on each side.
        val mapper = OverlayMapper(400, 300, 300f, 300f)
        assertEquals(-50f, mapper.mapX(0f), 0.001f)
        assertEquals(150f, mapper.mapX(200f), 0.001f)
        assertEquals(0f, mapper.mapY(0f), 0.001f)
    }

    @Test
    fun `fill center scales up small images`() {
        val mapper = OverlayMapper(100, 200, 300f, 600f)
        assertEquals(300f, mapper.mapX(100f), 0.001f)
        assertEquals(600f, mapper.mapY(200f), 0.001f)
    }
}
