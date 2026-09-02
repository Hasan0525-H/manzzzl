package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

private const val DEFAULT_HOUSE_MODEL_URL =
    "https://pub-08eacaf5a9fd4334815774c96664ac02.r2.dev/house.glb"

@Composable
fun SceneModelView(
    modelUrl: String = DEFAULT_HOUSE_MODEL_URL
) {
    val context = LocalContext.current
    val localModelPath = remember { mutableStateOf<String?>(null) }
    val loadingError = remember { mutableStateOf<String?>(null) }
    val cameraResetKey = remember { mutableStateOf(0) }
    val modelReady = remember { mutableStateOf(false) }

    LaunchedEffect(modelUrl) {
        try {
            val file = ModelRepository.getModelFile(context, modelUrl)
            localModelPath.value = file.absolutePath
        } catch (e: Exception) {
            loadingError.value = e.message ?: "Failed to load model"
        }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraManipulator = rememberCameraManipulator()

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraManipulator = cameraManipulator,
            key = cameraResetKey.value
        ) {
            localModelPath.value?.let { path ->
                rememberModelInstance(modelLoader, path)?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.0f,
                        autoAnimate = true
                    ).also {
                        if (!modelReady.value) {
                            modelReady.value = true
                        }
                    }
                }
            }
        }

        when {
            loadingError.value != null -> Text(
                text = loadingError.value ?: "Error",
                modifier = Modifier.align(Alignment.Center)
            )
            localModelPath.value == null || !modelReady.value -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Button(
            onClick = {
                cameraResetKey.value++
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text("إعادة ضبط الكاميرا")
        }
    }
}
