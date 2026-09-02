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
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import com.manzzzl.ai.model3d.CameraController
import com.manzzzl.ai.model3d.HouseModelController
import com.manzzzl.ai.model3d.LightingController
import com.manzzzl.ai.model3d.ModelBoundsCalculator

private const val DEFAULT_HOUSE_MODEL_URL =
    "https://pub-08eacaf5a9fd4334815774c96664ac02.r2.dev/house.glb"

@Composable
fun SceneModelView(
    modelUrl: String = DEFAULT_HOUSE_MODEL_URL
) {
    val context = LocalContext.current
    val localModelPath = remember { mutableStateOf<String?>(null) }
    val loadingError = remember { mutableStateOf<String?>(null) }
    val modelReady = remember { mutableStateOf(false) }
    val houseController = remember { HouseModelController() }
    val cameraController = remember { CameraController() }
    val lightingController = remember { LightingController() }

    // Load model from URL
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
            cameraManipulator = cameraManipulator
        ) {
            // Setup lighting (Filament provides default scene lighting)
            lightingController.setup()

            localModelPath.value?.let { path ->
                rememberModelInstance(modelLoader, path)?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.0f,
                        autoAnimate = true
                    ).also { node ->
                        houseController.attach(node)
                        
                        // Auto-frame the model when it's ready
                        try {
                            val bounds = ModelBoundsCalculator.calculate(node)
                            val cameraFrame = cameraController.frame(bounds)
                            // Apply camera framing via manipulator
                            cameraManipulator?.let { manipulator ->
                                manipulator.cameraNode?.position = 
                                    com.google.android.filament.math.Float3(
                                        cameraFrame.position[0],
                                        cameraFrame.position[1],
                                        cameraFrame.position[2]
                                    )
                                manipulator.cameraNode?.target = 
                                    com.google.android.filament.math.Float3(
                                        cameraFrame.target[0],
                                        cameraFrame.target[1],
                                        cameraFrame.target[2]
                                    )
                            }
                        } catch (e: Exception) {
                            loadingError.value = "Framing error: ${e.message}"
                        }
                        
                        modelReady.value = true
                    }
                }
            }
        }

        // Reset camera button
        IconButton(
            modifier = Modifier.align(Alignment.TopEnd),
            onClick = {
                try {
                    cameraController.reset()?.let { cameraFrame ->
                        cameraManipulator?.cameraNode?.let { camera ->
                            camera.position = com.google.android.filament.math.Float3(
                                cameraFrame.position[0],
                                cameraFrame.position[1],
                                cameraFrame.position[2]
                            )
                            camera.target = com.google.android.filament.math.Float3(
                                cameraFrame.target[0],
                                cameraFrame.target[1],
                                cameraFrame.target[2]
                            )
                        }
                    }
                } catch (e: Exception) {
                    loadingError.value = "Reset failed: ${e.message}"
                }
            }
        ) {
            Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = "Reset camera"
            )
        }

        when {
            loadingError.value != null -> Text(
                text = loadingError.value ?: "Error",
                modifier = Modifier.align(Alignment.Center)
            )
            !modelReady.value -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
