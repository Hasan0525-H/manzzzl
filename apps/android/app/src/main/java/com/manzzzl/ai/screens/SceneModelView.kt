package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Stable 3D viewer entry point.
 * The renderer implementation will be attached here.
 */
@Composable
fun SceneModelView(modelPath: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("3D viewer placeholder: $modelPath")
    }
}
