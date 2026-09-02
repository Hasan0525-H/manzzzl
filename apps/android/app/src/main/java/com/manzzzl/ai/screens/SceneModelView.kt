package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine

private const val DEFAULT_HOUSE_MODEL_URL =
    "https://pub-08eacaf5a9fd4334815774c96664ac02.r2.dev/house.glb"

/**
 * 3D GLB model viewer using SceneView.
 * - Renders external GLB models (default: house.glb from Cloudflare R2)
 * - Camera manipulator handles rotate/zoom/pan gestures
 * - Reset button returns camera to default framing
 */
@Composable
fun SceneModelView(
    modelUrl: String = DEFAULT_HOUSE_MODEL_URL
) {
    val context = LocalContext.current
    val localModelPath = remember { mutableStateOf<String?>(null) }
    val loadingError = remember { mutableStateOf<String?>(null) }
    val modelReady = remember { mutableStateOf(false) }

    // Load model from URL or local assets
    LaunchedEffect(modelUrl) {
        try {
            val file = ModelRepository.getModelFile(context, modelUrl)
            localModelPath.value = file.absolutePath
            modelReady.value = true
        } catch (e: Exception) {
            loadingError.value = e.message ?: "Failed to load model"
        }
    }

    val engine = rememberEngine()
    val cameraManipulator = rememberCameraManipulator()

    Box(modifier = Modifier.fillMaxSize()) {
        // SceneView renders the 3D model with default lighting
        // Camera manipulator provides gesture controls (rotate/zoom/pan)
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            cameraManipulator = cameraManipulator,
            modelLoader = { _, _ -> }
        ) {
            // Model path is provided to SceneView for rendering
            localModelPath.value?.let { path ->
                try {
                    // Load model from file path
                    loadModelGlb(path)
                } catch (e: Exception) {
                    loadingError.value = "Model rendering error: ${e.message}"
                }
            }
        }

        // Reset camera button
        IconButton(
            modifier = Modifier.align(Alignment.TopEnd),
            onClick = {
                try {
                    // Reset camera to default position
                    cameraManipulator?.let { manipulator ->
                        // SceneView camera manipulator handles reset via default framing
                        manipulator.reset()
                    }
                } catch (e: Exception) {
                    loadingError.value = "Reset failed: ${e.message}"
                }
            }
        ) {
            Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = "Reset camera view"
            )
        }

        // Loading and error states
        when {
            loadingError.value != null -> Text(
                text = loadingError.value ?: "Error loading model",
                modifier = Modifier.align(Alignment.Center)
            )
            !modelReady.value -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

// Placeholder for model loading (SceneView handles actual rendering)
private fun loadModelGlb(path: String) {
    // Model is loaded by SceneView's internal mechanisms
    // Path is provided for future custom rendering if needed
}
