package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Experimental 3D viewer stage.
 * Temporary implementation to validate the screen flow before GLB engine wiring.
 */
@Composable
fun SceneModelView(modelPath: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Experimental 3D stage: $modelPath")
    }
}
