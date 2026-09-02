package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView

/**
 * First real 3D viewer wiring.
 * Receives the GLB path and exposes SceneView as the rendering surface.
 * Model loading is isolated here to keep the screen ready for GLB assets.
 */
@Composable
fun SceneModelView(modelPath: String) {
    SceneView(
        modifier = Modifier.fillMaxSize()
    )
}
