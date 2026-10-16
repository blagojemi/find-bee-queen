package lab.mgb.findqueen.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRecordTest {
    private val base = DebugRecord(
        tMs = 1234, fps = 18.25f, width = 480, height = 640, rotation = 90,
        brightness = 101.26, sharpness = 3176.0, problem = null, beeScore = 0.5432f,
        outcome = "UNSURE", statsMs = 2, beeMs = 43, thumb = "thumb_000001234.jpg",
        fullScore = 0.31f, cropScore = 0.5432f,
    )

    @Test
    fun `checked frame`() {
        assertEquals(
            "1234,18.3,480,640,90,101.3,3176.0,,0.543,UNSURE,2,43,thumb_000001234.jpg,0.310,0.543",
            base.toCsvLine(),
        )
    }

    @Test
    fun `skipped frame leaves bee columns empty`() {
        val line = base.copy(
            problem = "blurry", beeScore = null, outcome = "SKIPPED", beeMs = null, thumb = null,
            fullScore = null, cropScore = null,
        ).toCsvLine()
        assertEquals("1234,18.3,480,640,90,101.3,3176.0,blurry,,SKIPPED,2,,,,", line)
    }

    @Test
    fun `line has one value per header column`() {
        val columns = DebugRecord.CSV_HEADER.split(",").size
        assertEquals(columns, base.toCsvLine().split(",").size)
        assertEquals(columns, base.copy(problem = "too dark", beeScore = null, thumb = null).toCsvLine().split(",").size)
    }
}
