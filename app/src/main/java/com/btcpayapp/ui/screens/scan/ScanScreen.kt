package com.btcpayapp.ui.screens.scan
import com.btcpayapp.core.util.safeStartActivity
import androidx.activity.compose.LocalActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Log
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.nav.ScanPurpose
import com.btcpayapp.ui.theme.LocalReducedMotion
import com.btcpayapp.ui.theme.Motion
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Which of the three ways in is on screen.
 *
 * A discriminator rather than the three booleans it is derived from, so the
 * region swaps once when the mode changes instead of on every recomposition
 * that touches the torch or the typed value.
 */
private enum class ScanMode { Typed, Camera, Blocked }

/**
 * The shared QR scanner.
 *
 * Decoding is done in-process by ZXing against the camera's luminance plane.
 * The alternative, ML Kit, would pull in Google Play services and an opaque,
 * self-updating model — a large and unnecessary increase in what this app
 * trusts, for a job that is a few hundred lines of well-understood maths.
 *
 * Frames never leave the device and are never written to storage.
 */
@Composable
fun ScanScreen(
    purpose: String,
    onResult: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permissionDenied by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var torch by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var manualValue by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it; permissionDenied = !it }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AppScreen(
        title = purpose.title(),
        onBack = onBack,
        actions = {
            IconButton(onClick = { manualEntry = !manualEntry }) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = "Type it instead")
            }
            if (granted) {
                IconButton(onClick = { torch = !torch }) {
                    Icon(
                        imageVector = if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = "Torch",
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val mode = when {
                manualEntry -> ScanMode.Typed
                granted -> ScanMode.Camera
                else -> ScanMode.Blocked
            }

            AnimatedSwap(mode, label = "scan") { current ->
                when (current) {
                    ScanMode.Typed -> ManualEntry(
                        value = manualValue,
                        purpose = purpose,
                        onValueChange = { manualValue = it },
                        onSubmit = {
                            if (accepts(purpose, manualValue)) onResult(manualValue.trim())
                        },
                    )

                    ScanMode.Camera -> CameraPreview(
                        torch = torch,
                        purpose = purpose,
                        onDecoded = onResult,
                    )

                    ScanMode.Blocked -> EmptyState(
                        title = "Camera access is off",
                        description = "Scanning needs the camera. Nothing is recorded — frames are " +
                            "decoded in memory and discarded.",
                        icon = Icons.Rounded.PhotoCamera,
                        actionLabel = if (permissionDenied && activity != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) "Open Android Settings" else "Allow camera",
                        onAction = {
                            if (permissionDenied && activity != null &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                                context.safeStartActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ))
                            } else permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    torch: Boolean,
    purpose: String,
    onDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    // One result only. Without this a fast scanner fires the callback several
    // times before navigation completes, and the caller sees duplicates.
    val consumed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val camera = remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val boundProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val boundAnalysis = remember { mutableStateOf<ImageAnalysis?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // `bindToLifecycle` only releases on the *lifecycle owner's*
            // destruction, and the owner here is the nav back-stack entry — not
            // this composable. Without this, leaving the preview while the entry
            // is still resumed (tapping "Type it instead") would keep the sensor
            // streaming, leave the torch lit with no way to turn it off, and
            // keep the analyzer submitting to an executor being shut down.
            runCatching { camera.value?.cameraControl?.enableTorch(false) }
            runCatching { boundAnalysis.value?.clearAnalyzer() }
            runCatching { boundProvider.value?.unbindAll() }
            camera.value = null
            boundProvider.value = null
            boundAnalysis.value = null
            executor.shutdown()
        }
    }

    LaunchedEffect(torch) {
        camera.value?.cameraControl?.enableTorch(torch)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = MultiFormatReader().apply {
                        setHints(
                            mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.TRY_HARDER to true,
                            ),
                        )
                    }

                    analysis.setAnalyzer(executor) { image ->
                        val text = image.use { decodeQr(reader, it) }
                        if (text != null && accepts(purpose, text) && consumed.compareAndSet(false, true)) {
                            previewView.post { currentOnDecoded(text) }
                        }
                    }

                    runCatching {
                        provider.unbindAll()
                        camera.value = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        // Kept so `onDispose` can release them.
                        boundProvider.value = provider
                        boundAnalysis.value = analysis
                    }.onFailure { Log.e("ScanScreen", it) { "could not bind the camera" } }
                }, ContextCompat.getMainExecutor(viewContext))

                previewView
            },
        )

        ViewfinderOverlay()

        Text(
            text = purpose.hint(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/** How bright the frame gets, and how far it fades between breaths. */
private const val FRAME_ALPHA = 0.9f
private const val FRAME_ALPHA_FADED = 0.55f

/** Dims everything outside a rounded square, which measurably helps framing. */
@Composable
private fun ViewfinderOverlay() {
    // The frame breathes, and only the frame: the mask around it holds still.
    //
    // A viewfinder that moves is worse at its job, not better — this sits over
    // a live camera feed that the user is trying to aim, and anything with a
    // travelling edge (a sweeping line, a pulsing outline that changes size)
    // competes with the code for the eye and makes framing harder. Opacity
    // alone, over two full seconds, is enough to say the scanner is awake
    // rather than frozen, which is the only thing this has to communicate.
    //
    // Held as a `State` and read inside the draw lambda rather than unwrapped
    // here, so a frame of the animation invalidates the drawing and nothing
    // else. Read in the composable body it would recompose this overlay sixty
    // times a second on top of a camera preview and an image analyser.
    val alpha: State<Float> = if (LocalReducedMotion.current) {
        remember { mutableStateOf(FRAME_ALPHA) }
    } else {
        val transition = rememberInfiniteTransition(label = "viewfinder")
        transition.animateFloat(
            initialValue = FRAME_ALPHA,
            targetValue = FRAME_ALPHA_FADED,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.PULSE_PERIOD_MS, easing = Motion.emphasised),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    }

    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = 24.dp.toPx()

        drawRect(color = Color.Black.copy(alpha = 0.55f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = GeometrySize(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = Color.White.copy(alpha = alpha.value),
            topLeft = Offset(left, top),
            size = GeometrySize(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun ManualEntry(
    value: String,
    purpose: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FormField(
            label = purpose.title(),
            value = value,
            onValueChange = onValueChange,
            placeholder = purpose.hint(),
            singleLine = false,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank() && accepts(purpose, value),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("Use this")
        }
    }
}

/**
 * ZXing wants a luminance plane. `YUV_420_888` puts luma first and already
 * matches, so this is a straight copy of plane 0 with no colour conversion —
 * the cheapest path, and the one that keeps the analyser off the main thread's
 * budget.
 */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

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

    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        // A frame without a code throws NotFoundException. That is the common
        // case, not an error worth logging thirty times a second.
        null
    } finally {
        reader.reset()
    }
}

/**
 * Rejects codes that cannot possibly be what the caller asked for, so the
 * scanner does not close on the wrong thing and make the user start again.
 */
private fun accepts(purpose: String, raw: String): Boolean {
    val parsed = ScanParser.parse(raw)
    return when (purpose) {
        ScanPurpose.SERVER -> parsed is ScannedPayload.ServerUrl || parsed is ScannedPayload.Invitation
        ScanPurpose.LIGHTNING_INVOICE -> parsed is ScannedPayload.Bolt11 ||
            parsed is ScannedPayload.Lnurl ||
            (parsed as? ScannedPayload.Bip21)?.lightning != null
        ScanPurpose.ONCHAIN_DESTINATION -> parsed is ScannedPayload.BitcoinAddress || parsed is ScannedPayload.Bip21
        ScanPurpose.SEND_DESTINATION -> parsed is ScannedPayload.BitcoinAddress ||
            parsed is ScannedPayload.Bip21 ||
            parsed is ScannedPayload.Bolt11 ||
            parsed is ScannedPayload.Lnurl
        ScanPurpose.PAYOUT_DESTINATION -> parsed !is ScannedPayload.Unknown && parsed !is ScannedPayload.ServerUrl
        ScanPurpose.NODE_URI -> parsed is ScannedPayload.NodeUri
        else -> parsed !is ScannedPayload.Unknown
    }
}

private fun String.title(): String = when (this) {
    ScanPurpose.SERVER -> "Scan a server code"
    ScanPurpose.LIGHTNING_INVOICE -> "Scan a Lightning invoice"
    ScanPurpose.ONCHAIN_DESTINATION -> "Scan an address"
    ScanPurpose.SEND_DESTINATION -> "Scan a payment"
    ScanPurpose.PAYOUT_DESTINATION -> "Scan a destination"
    ScanPurpose.NODE_URI -> "Scan a node address"
    else -> "Scan"
}

private fun String.hint(): String = when (this) {
    ScanPurpose.SERVER -> "A BTCPay server URL or login code"
    ScanPurpose.LIGHTNING_INVOICE -> "A BOLT11 invoice or LNURL"
    ScanPurpose.ONCHAIN_DESTINATION -> "A bitcoin address or BIP21 link"
    ScanPurpose.SEND_DESTINATION -> "An address, BIP21 link or Lightning invoice"
    ScanPurpose.PAYOUT_DESTINATION -> "An address, invoice or LNURL"
    ScanPurpose.NODE_URI -> "pubkey@host:port"
    else -> "Point the camera at a QR code"
}
