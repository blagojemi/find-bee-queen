package lab.mgb.findqueen.detection

import android.graphics.RectF
import kotlin.math.max

/**
 * Maps boxes from image pixel space to view space, matching PreviewView's FILL_CENTER
 * (scale to cover the view, then center-crop).
 */
class OverlayMapper(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float) {
    private val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    private val offsetX = (viewWidth - imageWidth * scale) / 2f
    private val offsetY = (viewHeight - imageHeight * scale) / 2f

    fun mapX(x: Float) = x * scale + offsetX
    fun mapY(y: Float) = y * scale + offsetY

    fun map(box: RectF): FloatArray =
        floatArrayOf(mapX(box.left), mapY(box.top), mapX(box.right), mapY(box.bottom))
}
