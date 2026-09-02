package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

private const val DEFAULT_HOUSE_MODEL_URL =
    "https://pub-08eacaf5a9fd4334815774c96664ac02.r2.dev/house.glb"

/**
 * 3D GLB model viewer using SceneView 2.2.1.
 * 
 * Features:
 * - Loads GLB models from Cloudflare R2 or local assets
 * - Caches downloaded models to local storage
 * - Error handling for network and file loading failures
 * - Loading state with spinner
 * 
 * TODO: Wire SceneView composable once arsceneview 2.2.1 API is confirmed stable.
 * Currently the model file is fetched and cached via ModelRepository.
 */
@Composable
fun SceneModelView(
    modelUrl: String = DEFAULT_HOUSE_MODEL_URL
) {
    val context = LocalContext.current
    val localModelPath = remember { mutableStateOf<String?>(null) }
    val loadingError = remember { mutableStateOf<String?>(null) }
    val modelReady = remember { mutableStateOf(false) }

    // Load model from URL or local assets on composition
    LaunchedEffect(modelUrl) {
        try {
            // Fetch model using ModelRepository (handles caching and network timeouts)
            val file = ModelRepository.getModelFile(context, modelUrl)
            localModelPath.value = file.absolutePath
            modelReady.value = true
        } catch (e: Exception) {
            loadingError.value = e.message ?: "Failed to load model"
        }
    }

    // Main UI layout
    Box(modifier = Modifier.fillMaxSize()) {
        // 3D Model Display
        // SceneView composable will be initialized here once API is stabilized
        // The model file path is available at: localModelPath.value
        
        // Display loading state
        if (!modelReady.value && loadingError.value == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Display error state
        if (loadingError.value != null) {
            Text(
                text = loadingError.value ?: "Error loading model",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
