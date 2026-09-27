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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.LifecycleOwner
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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    var hasFlash by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var manualValue by remember { mutableStateOf("") }

    // The camera cannot be used: none at the back, CameraX failing to start,
    // or a camera that a device policy disables or that fails once open. The
    // screen then offers typing, rather than an overlay over a black preview
    // that never changes.
    var cameraFailed by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it; permissionDenied = !it }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val mode = when {
        manualEntry -> ScanMode.Typed
        granted -> ScanMode.Camera
        else -> ScanMode.Blocked
    }

    AppScreen(
        title = purpose.title(),
        onBack = onBack,
        actions = {
            // Labelled for where it leads, so the same button does not say
            // "type it instead" to someone who is already typing.
            IconButton(
                onClick = {
                    // Back to the camera is also a retry after a failure.
                    if (manualEntry) cameraFailed = false
                    manualEntry = !manualEntry
                },
            ) {
                Icon(
                    imageVector = if (manualEntry) Icons.Rounded.PhotoCamera else Icons.Rounded.ContentPaste,
                    contentDescription = if (manualEntry) "Use the camera" else "Type it instead",
                )
            }
            // Only for a camera that has a flash, and as a switch a screen
            // reader can read the state of.
            if (mode == ScanMode.Camera && hasFlash) {
                IconToggleButton(
                    checked = torch,
                    onCheckedChange = { torch = it },
                    modifier = Modifier.semantics { stateDescription = if (torch) "On" else "Off" },
                ) {
                    Icon(
                        imageVector = if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = "Torch",
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedSwap(mode, label = "scan") { current ->
                when (current) {
                    ScanMode.Typed -> ManualEntry(
                        value = manualValue,
                        purpose = purpose,
                        notice = if (cameraFailed) "No usable camera. Type or paste the code instead." else null,
                        onValueChange = { manualValue = it },
                        onSubmit = {
                            if (accepts(purpose, manualValue)) onResult(manualValue.trim())
                        },
                    )

                    ScanMode.Camera -> CameraPreview(
                        torch = torch,
                        purpose = purpose,
                        onDecoded = onResult,
                        onReady = { flash -> hasFlash = flash },
                        onUnavailable = {
                            cameraFailed = true
                            manualEntry = true
                        },
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

/**
 * The live preview and the analyser. [onReady] reports whether the bound
 * camera has a flash; [onUnavailable] reports that no camera could be used.
 */
@Composable
private fun CameraPreview(
    torch: Boolean,
    purpose: String,
    onDecoded: (String) -> Unit,
    onReady: (hasFlash: Boolean) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    // One result only. Without this a fast scanner fires the callback several
    // times before navigation completes, and the caller sees duplicates.
    val consumed = remember { AtomicBoolean(false) }
    // False once the preview has left. CameraX answers asynchronously, and a
    // camera bound after that would stream with nothing left to release it.
    val active = remember { AtomicBoolean(true) }
    val camera = remember { mutableStateOf<Camera?>(null) }
    val boundProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val boundAnalysis = remember { mutableStateOf<ImageAnalysis?>(null) }
    // Another app holds the camera, or it is recovering from an error.
    // CameraX opens it again by itself, so the preview waits and says why.
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            runCatching { camera.value?.cameraInfo?.cameraState?.removeObservers(lifecycleOwner) }
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

    // Also keyed on the camera, so a torch switched on before the camera
    // was bound (or on a previous visit) is lit once it is.
    LaunchedEffect(torch, camera.value) {
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
                    if (!active.get()) return@addListener
                    // `get()` throws when CameraX itself failed to start, and
                    // the bind throws when there is no back camera or it cannot
                    // run these use cases. Either way, on the main thread:
                    // caught, so it becomes the typed fallback and not a crash.
                    runCatching {
                        bindCamera(
                            provider = providerFuture.get(),
                            previewView = previewView,
                            lifecycleOwner = lifecycleOwner,
                            executor = executor,
                            purpose = purpose,
                            onAccepted = { text -> previewView.post { currentOnDecoded(text) } },
                            consumed = consumed,
                        )
                    }.onSuccess { (provider, analysis, bound) ->
                        // Kept so `onDispose` can release them.
                        camera.value = bound
                        boundProvider.value = provider
                        boundAnalysis.value = analysis
                        currentOnReady(bound.cameraInfo.hasFlashUnit())
                        // A camera in use or disabled by a device policy still
                        // binds: the error arrives later, on its state.
                        bound.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                            if (!active.get()) return@observe
                            val error = state.error
                            busy = error?.type == CameraState.ErrorType.RECOVERABLE
                            if (error?.type == CameraState.ErrorType.CRITICAL) currentOnUnavailable()
                        }
                    }.onFailure {
                        Log.e("ScanScreen", it) { "could not start the camera" }
                        currentOnUnavailable()
                    }
                }, ContextCompat.getMainExecutor(viewContext))

                previewView
            },
        )

        ViewfinderOverlay()

        Text(
            text = if (busy) "The camera is busy. It starts once it is free." else purpose.hint(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Builds the preview and the analyser and binds both to the back camera.
 * Throws when that camera cannot be used; the caller turns that into the
 * typed fallback. [onAccepted] runs on the analyser thread, once.
 */
private fun bindCamera(
    provider: ProcessCameraProvider,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: ExecutorService,
    purpose: String,
    consumed: AtomicBoolean,
    onAccepted: (String) -> Unit,
): Triple<ProcessCameraProvider, ImageAnalysis, Camera> {
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

    // One buffer for the life of this analyser. A new one per frame is about
    // 0.9 MB, ten to twenty times a second, all of it garbage a moment later.
    val luma = LumaBuffer()
    analysis.setAnalyzer(executor) { image ->
        val text = image.use { decodeQr(reader, it, luma) }
        if (text != null && accepts(purpose, text) && consumed.compareAndSet(false, true)) {
            // Nothing after the first result is wanted, so the decoding stops
            // here rather than when navigation gets round to disposing it.
            analysis.clearAnalyzer()
            onAccepted(text)
        }
    }

    provider.unbindAll()
    val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    return Triple(provider, analysis, camera)
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

/** [notice] says why the camera is not offered, when that is the reason for typing. */
@Composable
private fun ManualEntry(
    value: String,
    purpose: String,
    notice: String?,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (notice != null) {
            Text(
                text = notice,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * The luma plane's bytes, in one array reused from frame to frame and
 * replaced only when the plane's size changes. Used from the analyser's
 * single thread only, and ZXing reads it only during the decode.
 */
private class LumaBuffer {
    private var bytes = ByteArray(0)

    fun read(buffer: ByteBuffer): ByteArray {
        val size = buffer.remaining()
        if (bytes.size != size) bytes = ByteArray(size)
        buffer.get(bytes)
        return bytes
    }
}

/**
 * ZXing wants a luminance plane. `YUV_420_888` puts luma first and already
 * matches, so this is a straight copy of plane 0 with no colour conversion —
 * the cheapest path, and the one that keeps the analyser off the main thread's
 * budget.
 */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy, luma: LumaBuffer): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val bytes = luma.read(plane.buffer)

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
