package com.govorun.echo.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.govorun.echo.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera over the whole screen and a frame to aim with; it closes on the first code [accept] takes. The picture is read on the
 * phone (ZXing) and goes nowhere.
 */
@Composable
internal fun QrScanner(
    accept: (String) -> Boolean,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
        DisposableEffect(lifecycle) {
            val worker = Executors.newSingleThreadExecutor()
            val found = AtomicBoolean(false)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> surface = request } }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build(),
                        ).build()
                analysis.setAnalyzer(worker) { image ->
                    val plane = image.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                    val text = QrDecode.read(bytes, plane.rowStride, image.width, image.height)
                    image.close()
                    if (text != null && accept(text) && found.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { onCode(text) }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { ContextCompat.getMainExecutor(context).execute(onClose) }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                runCatching { future.get().unbindAll() }
                worker.shutdown()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            surface?.let { CameraXViewfinder(it, Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White, RoundedCornerShape(24.dp)))
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
            }
            Text(
                stringResource(R.string.computer_scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp),
            )
        }
    }
}

/** Finds a QR code in the brightness of a camera frame (the Y plane), the way the camera hands it over. */
internal object QrDecode {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun read(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(hints) }
        // A dark page draws the code light on dark: try the picture as it is, then inverted.
        return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            ?: runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text }.getOrNull()
    }
}
