package com.calorieapp.ui.analyze

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.calorieapp.ui.meal.AnalysisState
import java.io.File

/** Shown while the photo is uploaded and analysed; offers Retry / Retake on failure. */
@Composable
fun AnalyzeScreen(
    photo: File?,
    state: AnalysisState,
    onSuccess: () -> Unit,
    onRetry: () -> Unit,
    onRetake: () -> Unit,
) {
    LaunchedEffect(state) {
        when (state) {
            is AnalysisState.Success -> onSuccess()
            AnalysisState.Idle -> onRetake() // e.g. app was killed and restored mid-flow
            else -> Unit
        }
    }
    BackHandler(onBack = onRetake)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))

        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is AnalysisState.Error -> {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        state.message,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRetake) { Text("Retake", color = Color.White) }
                        if (state.canRetry) Button(onClick = onRetry) { Text("Retry") }
                    }
                }

                else -> {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(20.dp))
                    Text("Analysing your meal…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This usually takes 5–20 seconds",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
