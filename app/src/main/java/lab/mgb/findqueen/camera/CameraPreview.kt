package lab.mgb.findqueen.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Back-camera preview with an optional frame analyzer and an optional still-capture
 * use case, all bound to the same lifecycle. [onBound] runs once the camera is bound
 * and still captures can be taken.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer?,
    modifier: Modifier = Modifier,
    imageCapture: ImageCapture? = null,
    onBound: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Declared first so it is disposed last, after the camera has been unbound.
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    DisposableEffect(lifecycleOwner, analyzer, imageCapture) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val useCases = buildList {
                add(preview)
                if (analyzer != null) {
                    add(
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) },
                    )
                }
                if (imageCapture != null) add(imageCapture)
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases.toTypedArray(),
            )
            onBound()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
