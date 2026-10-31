package pl.wspa.filum.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.wspa.filum.app.qr.QrDecoder
import pl.wspa.filum.protocol.PairingCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the pairing code from the server panel with the camera (ADR-018).
 *
 * Only a code that parses as a Filum pairing code is taken; anything else - a link on a
 * poster, another app's code - is pointed out and scanning goes on. The camera runs only
 * while this screen is shown.
 */
@Composable
fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current

    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var refused by remember { mutableStateOf(false) }
    var sawOtherCode by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        refused = !granted
    }
    LaunchedEffect(Unit) {
        if (!permitted) request.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Scan the code", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Point the camera at the QR code in the Filum panel on the computer.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (permitted) {
            CameraPreview(
                onCode = onCode,
                onOtherCode = { sawOtherCode = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            if (sawOtherCode) {
                Text(
                    "That is not a Filum pairing code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (refused) {
            Text(
                "Without the camera, paste the code instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, onOtherCode: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnOtherCode by rememberUpdatedState(onOtherCode)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        val analysisThread = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val delivered = AtomicBoolean(false)
        var disposed = false

        // Both this listener and onDispose run on the main thread, so a screen left before
        // the camera came up can never be followed by the camera coming up anyway.
        providerFuture.addListener({
            if (disposed) return@addListener
            bindCamera(
                provider = providerFuture.get(),
                owner = lifecycleOwner,
                previewView = previewView,
                analyzer = PairingCodeAnalyzer(
                    onCode = { code ->
                        if (delivered.compareAndSet(false, true)) main.execute { currentOnCode(code) }
                    },
                    onOtherCode = { main.execute { currentOnOtherCode() } },
                ),
                analysisThread = analysisThread,
            )
        }, main)

        onDispose {
            disposed = true
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            analysisThread.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer,
    analysisThread: java.util.concurrent.Executor,
) {
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

    // Only the newest frame matters: decoding can be slower than the camera, and a queue of
    // stale frames would only delay the moment the code is read.
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisThread, analyzer) }

    provider.unbindAll()
    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
}

/** Decodes camera frames and sorts what it finds into Filum pairing codes and everything else. */
private class PairingCodeAnalyzer(
    private val onCode: (String) -> Unit,
    private val onOtherCode: () -> Unit,
) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()
    private var frame = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            // The first plane of a YUV_420_888 frame is the luminance, one byte per pixel.
            val plane = it.planes[0]
            val buffer = plane.buffer
            if (frame.size != buffer.remaining()) frame = ByteArray(buffer.remaining())
            buffer.get(frame)

            val text = decoder.decode(frame, it.width, it.height, plane.rowStride) ?: return
            if (PairingCode.parse(text) is PairingCode.Result.Ok) onCode(text) else onOtherCode()
        }
    }
}
