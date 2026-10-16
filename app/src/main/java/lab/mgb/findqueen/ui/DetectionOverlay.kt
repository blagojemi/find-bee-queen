package lab.mgb.findqueen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import lab.mgb.findqueen.detection.FrameResult
import lab.mgb.findqueen.detection.OverlayMapper

private val QueenColor = Color(0xFFFFB300)

@Composable
fun DetectionOverlay(result: FrameResult?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (result == null) return@Canvas
        val mapper = OverlayMapper(result.imageWidth, result.imageHeight, size.width, size.height)
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14.dp.toPx()
            isAntiAlias = true
        }
        for (detection in result.detections) {
            val (left, top, right, bottom) = mapper.map(detection.box)
            drawRect(
                color = QueenColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 3.dp.toPx()),
            )
            val label = "Queen ${(detection.score * 100).toInt()}%"
            val labelHeight = textPaint.textSize + 8.dp.toPx()
            drawRect(
                color = QueenColor,
                topLeft = Offset(left, top - labelHeight),
                size = Size(textPaint.measureText(label) + 12.dp.toPx(), labelHeight),
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                left + 6.dp.toPx(),
                top - 4.dp.toPx() - textPaint.descent(),
                textPaint,
            )
        }
    }
}
