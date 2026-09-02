package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * MVP 3D viewer entry point.
 *
 * This replaces the old information-only screen and keeps one stable place
 * for the real GLB renderer integration.
 */
@Composable
fun SceneModelView(modelPath: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("3D Viewer Ready: $modelPath")
    }
}
