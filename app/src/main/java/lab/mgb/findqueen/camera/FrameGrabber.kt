package lab.mgb.findqueen.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One frame grabbed from the camera, already rotated upright. */
class GrabbedFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int,
    val captureMs: Long,
)

/** Takes a single in-memory picture from [ImageCapture] (nothing is written to disk). */
suspend fun ImageCapture.grabFrame(executor: Executor): GrabbedFrame {
    val start = System.currentTimeMillis()
    val image = suspendCancellableCoroutine<ImageProxy> { cont ->
        takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (cont.isActive) cont.resume(image) else image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }
    return image.use {
        val rotation = it.imageInfo.rotationDegrees
        GrabbedFrame(
            bitmap = it.toBitmap().rotate(rotation),
            rotationDegrees = rotation,
            captureMs = System.currentTimeMillis() - start,
        )
    }
}

internal fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
