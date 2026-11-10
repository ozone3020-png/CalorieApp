package com.calorieapp.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.calorieapp.data.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Live camera preview with a shutter button and a gallery button, a Meal / Label switch, and a review
 * screen (Retake / Use photo). The confirmed photo and mode are handed to [onPhotoConfirmed].
 */
@Composable
fun CameraScreen(
    onPhotoConfirmed: (File, ScanMode) -> Unit,
    onBarcode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ScanMode.MEAL) }
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        askedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !askedOnce) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Picks up a grant made in system settings while the app was in the background.
    LifecycleResumeEffect(Unit) {
        hasPermission = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    // Path (not File) so it survives rotation / process death via rememberSaveable.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }

    // Android's photo picker: no storage permission needed. The pick is copied into the app's cache so it
    // can be shrunk, uploaded and deleted like a camera photo, without touching the original.
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val copy = context.copyToPhotos(uri)
                if (copy != null) photoPath = copy.absolutePath
                else Toast.makeText(context, "Couldn't open that photo", Toast.LENGTH_LONG).show()
            }
        }
    }
    // Google's code scanner shows its own camera screen; it needs no permission and works on product barcodes.
    val scanBarcode = {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .enableAutoZoom()
                .build(),
        ).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onBarcode) }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Barcode scanner unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
        Unit
    }
    val pickFromGallery = {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Box(modifier.background(Color.Black)) {
        when {
            !hasPermission && photoPath == null -> PermissionPrompt(
                showSettings = askedOnce,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
                onPickFromGallery = pickFromGallery,
            )

            photoPath == null -> CameraCapture(
                mode = mode,
                onCaptured = { photoPath = it.absolutePath },
                onPickFromGallery = pickFromGallery,
                onScanBarcode = scanBarcode,
            )

            else -> PhotoReview(
                file = File(photoPath!!),
                onRetake = {
                    File(photoPath!!).delete()
                    photoPath = null
                },
                onUse = {
                    // Hand ownership of the file to the caller; come back to a live viewfinder.
                    val file = File(photoPath!!)
                    photoPath = null
                    onPhotoConfirmed(file, mode)
                },
            )
        }
        if (hasPermission || photoPath != null) {
            ModeSwitch(
                mode = mode,
                onModeChange = { mode = it },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            )
        }
    }
}

/** Meal (identify foods on a plate) or Label (read a nutrition information panel). */
@Composable
private fun ModeSwitch(mode: ScanMode, onModeChange: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        listOf(ScanMode.MEAL to "Meal", ScanMode.LABEL to "Label").forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                colors = SegmentedButtonDefaults.colors(
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color.White,
                ),
            ) { Text(label) }
        }
    }
}

@Composable
private fun CameraCapture(
    mode: ScanMode,
    onCaptured: (File) -> Unit,
    onPickFromGallery: () -> Unit,
    onScanBarcode: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    var capturing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
        )

        if (mode == ScanMode.LABEL) {
            Text(
                "Fill the frame with the nutrition panel, flat on and in focus",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 32.dp, end = 32.dp, bottom = 128.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            FilledTonalIconButton(
                onClick = onPickFromGallery,
                enabled = !capturing,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 40.dp).size(52.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose from gallery")
            }
            FilledTonalIconButton(
                onClick = onScanBarcode,
                enabled = !capturing,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 40.dp).size(52.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan a barcode")
            }
            FilledIconButton(
                onClick = {
                    capturing = true
                    controller.takePhoto(
                        context = context,
                        onSaved = { file ->
                            capturing = false
                            onCaptured(file)
                        },
                        onError = { e ->
                            capturing = false
                            Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
                        },
                    )
                },
                enabled = !capturing,
                modifier = Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (capturing) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo", Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun PhotoReview(file: File, onRetake: () -> Unit, onUse: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = file,
            contentDescription = "Captured meal",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Retake", color = Color.White)
            }
            Button(onClick = onUse, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Use photo")
            }
        }
    }
}

@Composable
private fun PermissionPrompt(
    showSettings: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFromGallery: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Camera access is needed to photograph your meals.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        if (showSettings) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPickFromGallery) { Text("Choose a photo from the gallery instead") }
    }
}

private fun LifecycleCameraController.takePhoto(
    context: Context,
    onSaved: (File) -> Unit,
    onError: (ImageCaptureException) -> Unit,
) {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File(dir, "meal_${System.currentTimeMillis()}.jpg")
    takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) = onError(exception)
        },
    )
}

/** Copies a picked image into the app's photo cache; null if it can't be read. */
private suspend fun Context.copyToPhotos(uri: Uri): File? = withContext(Dispatchers.IO) {
    try {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "picked_${System.currentTimeMillis()}.jpg")
        val input = contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { src -> file.outputStream().use { src.copyTo(it) } }
        file
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
