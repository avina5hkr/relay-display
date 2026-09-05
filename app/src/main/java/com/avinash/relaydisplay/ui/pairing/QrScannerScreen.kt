package com.avinash.relaydisplay.ui.pairing

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.relayViewModel
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * The in-app QR scanner for pairing.
 *
 * Camera permission is requested here and nowhere else, at the moment the user taps Scan, with
 * the reason on screen first. Decoding is done locally with ZXing on the analyser's own thread;
 * no frame ever leaves the device and none is stored.
 */
@Composable
fun QrScannerScreen(
    onCancel: () -> Unit,
    onAccepted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = relayViewModel { PairingViewModel.create(it) },
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        // A denial that arrives instantly, with no dialog, is the "never ask again" case.
        if (!result) permanentlyDenied = true
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(RelayDimens.ScreenPadding),
    ) {
        Text("Scan the pairing code", style = MaterialTheme.typography.displaySmall)
        VerticalGap()

        when {
            granted -> {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("camera_preview")) {
                    CameraPreview(
                        onDecoded = { text ->
                            if (viewModel.onScanned(text)) {
                                onAccepted()
                            } else {
                                lastError = "That code was not accepted. Show a fresh one."
                            }
                        },
                        onCameraError = { lastError = it },
                    )
                }
                lastError?.let {
                    VerticalGap(RelayDimens.SmallGap)
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            permanentlyDenied -> RelayCard {
                Text("Camera access is off", style = MaterialTheme.typography.titleLarge)
                VerticalGap(RelayDimens.SmallGap)
                Text(
                    "Scanning needs the camera. You can turn it on in Settings, Apps, " +
                        "RelayDisplay, Permissions -- or pair by entering the display's address " +
                        "instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                VerticalGap()
                SecondaryAction("Go back and pair another way", onClick = onCancel)
            }

            else -> RelayCard {
                Text("Camera access", style = MaterialTheme.typography.titleLarge)
                VerticalGap(RelayDimens.SmallGap)
                Text(
                    "RelayDisplay needs the camera only to read the pairing code on the other " +
                        "phone. Nothing is recorded or sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                VerticalGap()
                PrimaryAction(
                    "Allow camera",
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("grant_camera"),
                )
            }
        }

        VerticalGap()
        SecondaryAction("Cancel", onClick = onCancel)
    }
}

/**
 * CameraX preview plus a bounded analyser.
 *
 * `STRATEGY_KEEP_ONLY_LATEST` means a slow decode drops frames instead of queueing them, which
 * is the only sane behaviour on the older phone; and the whole pipeline is unbound in `onDispose`
 * so the camera light never stays on after the screen goes away.
 */
@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { QRCodeReader() }
    var handled by remember { mutableStateOf(false) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    if (!handled) {
                        decodeQr(reader, image)?.let { text ->
                            handled = true
                            previewView.post { onDecoded(text) }
                        }
                    }
                    image.close()
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: IllegalStateException) {
                onCameraError("The camera could not be started.")
            } catch (e: java.util.concurrent.ExecutionException) {
                onCameraError("The camera is not available on this phone.")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

/**
 * Decodes one frame's luminance plane.
 *
 * Only the Y plane is read, so there is no colour conversion and no extra allocation per frame
 * beyond the row buffer CameraX already owns.
 */
private fun decodeQr(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    return try {
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width.coerceAtMost(plane.rowStride),
            image.height,
            false,
        )
        reader.decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    } catch (e: NotFoundException) {
        // No code in this frame; entirely normal.
        null
    } catch (e: com.google.zxing.ChecksumException) {
        null
    } catch (e: com.google.zxing.FormatException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } finally {
        reader.reset()
    }
}
