package com.constrivo.drop.ui.android

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Finds a QR code in one camera frame's luminance plane with ZXing (design §4.4 "Scan to send"): the Y plane of a
 * `YUV_420_888` frame is luminance already, so no conversion is needed; the code may be at any angle. Not thread-safe:
 * one per analysis thread.
 */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        )

    /**
     * The text of a QR code in [luminance] ([width] × [height] pixels, [rowStride] bytes per row, one byte per pixel),
     * or null when the frame holds none that decodes.
     *
     * @throws IllegalArgumentException when the plane is smaller than its dimensions say.
     */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame geometry ${width}x$height stride $rowStride" }
        require(luminance.size >= rowStride * (height - 1) + width) { "the plane is smaller than the frame" }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }
}

/**
 * CameraX's frame analysis for the scanner: the latest frame only (a slow decode never queues frames), its Y plane to
 * [QrFrameDecoder], and each new text once to [onText] on the main thread; the same text again within
 * [REPEAT_MILLIS] is dropped (a code in view decodes on every frame).
 */
internal class QrAnalyzer(
    context: Context,
    private val onText: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : ImageAnalysis.Analyzer {
    private val main = ContextCompat.getMainExecutor(context)
    private val decoder = QrFrameDecoder()
    private var plane = ByteArray(0)
    private var lastText: String? = null
    private var lastAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val y = image.planes.firstOrNull() ?: return
            val buffer = y.buffer
            val needed = y.rowStride * image.height
            if (plane.size < needed) plane = ByteArray(needed)
            val length = minOf(buffer.remaining(), plane.size)
            buffer.get(plane, 0, length)
            val text = decoder.decode(plane, image.width, image.height, y.rowStride) ?: return
            val at = now()
            if (text == lastText && at - lastAt < REPEAT_MILLIS) return
            lastText = text
            lastAt = at
            main.execute { onText(text) }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "a camera frame could not be read", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "Drop"
        const val REPEAT_MILLIS = 3_000L
    }
}

/**
 * The scanner's camera preview (the shared `ScanQrScreen`'s `camera` slot): the back camera through CameraX bound to
 * [lifecycleOwner] (the activity), a preview filling the screen and a frame analysis at about 1280 × 720 for
 * [QrAnalyzer]. Everything is released when the scanner leaves the screen. Needs `CAMERA`, which the controller asks
 * for before this shows.
 */
@Composable
internal fun QrCameraPreview(
    lifecycleOwner: LifecycleOwner,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
    DisposableEffect(lifecycleOwner, previewView) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    val cameras = future.get()
                    provider = cameras
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                ResolutionSelector
                                    .Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    ).build(),
                            ).build()
                            .also { it.setAnalyzer(executor, QrAnalyzer(context, onText)) }
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    // No back camera, or the camera is in use by another app: the screen stays dark with its text.
                    Log.w("Drop", "the camera could not start", e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720
