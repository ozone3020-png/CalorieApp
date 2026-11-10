package com.calorieapp.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
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
import java.io.File

/**
 * Step 1: live camera preview, a shutter button, and a review screen
 * (Retake / Use photo). The confirmed JPEG is handed to [onPhotoConfirmed].
 */
@Composable
fun CameraScreen(
    onPhotoConfirmed: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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

    Box(modifier.background(Color.Black)) {
        when {
            !hasPermission -> PermissionPrompt(
                showSettings = askedOnce,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )

            photoPath == null -> CameraCapture(onCaptured = { photoPath = it.absolutePath })

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
                    onPhotoConfirmed(file)
                },
            )
        }
    }
}

@Composable
private fun CameraCapture(onCaptured: (File) -> Unit) {
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

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
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
private fun PermissionPrompt(showSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
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

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
