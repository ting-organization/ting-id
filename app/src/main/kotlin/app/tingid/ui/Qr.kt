// QR codes and links (LINKS.md): showing a link to scan or share, and
// scanning or pasting one. Codes are made and read with ZXing, on the phone,
// with nothing from Google Play services.
package app.tingid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** The QR code of `text`: error correction M, the smallest version that fits, no quiet zone (drawn around it). */
fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))

/** A QR code, dark on white in both themes so every camera reads it. */
@Composable
fun QrCode(value: String, size: Dp = 216.dp) {
    val matrix = remember(value) { qrMatrix(value) }
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(RoundedCornerShape(Radius.md)).background(Color.White).border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(Radius.md)).padding(10.dp).semantics { contentDescription = "QR code" }) {
            Canvas(Modifier.size(size)) {
                val quiet = 2
                val cells = matrix.width + 2 * quiet
                val cell = this.size.width / cells
                for (y in 0 until matrix.height) {
                    var x = 0
                    while (x < matrix.width) {
                        if (!matrix[x, y]) {
                            x++
                            continue
                        }
                        val start = x
                        while (x < matrix.width && matrix[x, y]) x++
                        drawRect(Color(0xFF111827), Offset((start + quiet) * cell, (y + quiet) * cell), Size((x - start) * cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

/** A link to hand to someone: its QR code, the link, and buttons to copy it or share it in a message. */
@Composable
fun LinkShare(link: String, qr: Boolean = true) {
    val p = t
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (qr) Box(Modifier.padding(vertical = Spacing.xs)) { QrCode(link) }
        Text(link, style = Type.mono.copy(fontSize = Type.mono.fontSize * 0.88f), color = p.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                if (copied) "Copied" else "Copy link",
                onClick = {
                    scope.launch {
                        copyText(clipboard, link)
                        copied = true
                    }
                },
                kind = ButtonKind.Secondary,
                small = true,
                icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                modifier = Modifier.weight(1f),
            )
            Button(
                "Share",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, null))
                },
                kind = ButtonKind.Secondary,
                small = true,
                icon = Icons.Outlined.Share,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A full-screen camera that reads QR codes, or a link pasted from the
 * clipboard. `onScan` gets each new code and returns null when it took it
 * (the caller then closes the scanner) or a message to show while scanning
 * goes on.
 */
@Composable
fun QrScanner(onClose: () -> Unit, onScan: (String) -> String?, title: String = "Scan a code") {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scanner(onClose, onScan, title)
    }
}

@Composable
private fun Scanner(onClose: () -> Unit, onScan: (String) -> String?, title: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    // Opening the scanner is the request to use the camera: asked once.
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val scanned: (String) -> Unit = { data ->
        if (data != last) {
            last = data
            message = onScan(data)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraPreview(scanned)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x73000000)).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = manropeStyle(17f, FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(0x29FFFFFF)).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (granted) {
                    Box(Modifier.size(240.dp).border(3.dp, Color(0xE6FFFFFF), RoundedCornerShape(24.dp)))
                } else {
                    Column(Modifier.padding(horizontal = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Icon(Icons.Outlined.CameraAlt, null, tint = Color.White, modifier = Modifier.size(40.dp))
                        Text(
                            if (refused) "Camera access is turned off for this app. Turn it on in the system settings to scan codes." else "The camera is needed to scan a code.",
                            style = manropeStyle(15f), color = Color.White, textAlign = TextAlign.Center,
                        )
                        if (!refused) Button("Allow camera", onClick = { ask.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Light)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(Color(0x73000000)).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = Spacing.lg, vertical = Spacing.md).padding(bottom = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                val m = message
                if (m != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Color(0xFFFCD34D), modifier = Modifier.size(18.dp))
                        Text(m, style = manropeStyle(15f), color = Color.White, modifier = Modifier.weight(1f))
                    }
                } else if (granted) {
                    Text("Point the camera at a Ting QR code, or paste a link someone sent you.", style = manropeStyle(15f), color = Color.White, textAlign = TextAlign.Center)
                }
                Button(
                    "Paste a link",
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                            message = if (text.isNotEmpty()) onScan(text) else "Nothing to paste: copy the link first."
                        }
                    },
                    kind = ButtonKind.Raised,
                    icon = Icons.Outlined.ContentPaste,
                )
            }
        }
    }
}

/** The back camera, its frames read for QR codes on a thread of their own. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = QRCodeReader()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image -> decode(reader, image)?.let { code -> main.execute { onCode(code) } } }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, main)
            view
        },
    )
}

private fun decode(reader: QRCodeReader, image: ImageProxy): String? = image.use {
    val plane = it.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
    try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
