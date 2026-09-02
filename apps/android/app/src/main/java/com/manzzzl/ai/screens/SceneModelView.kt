package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Real 3D GLB viewer.
 * Loads a GLB model and renders it inside SceneView.
 */
@Composable
fun SceneModelView(modelPath: String) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val assetPath = resolveAssetPath(modelPath)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        rememberModelInstance(modelLoader, assetPath)?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                autoAnimate = true
            )
        }
    }
}

private fun resolveAssetPath(path: String): String {
    return path.removePrefix("glb://")
}
