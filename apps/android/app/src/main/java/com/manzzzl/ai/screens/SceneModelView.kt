package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * GLB viewer entry point.
 *
 * The model path is now treated as the source for the future renderer.
 * Keeping this boundary isolated allows the SceneView implementation
 * to be attached without changing the screen flow.
 */
@Composable
fun SceneModelView(modelPath: String) {
    val glbPath = resolveGlbPath(modelPath)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("GLB model ready: $glbPath")
    }
}

private fun resolveGlbPath(path: String): String {
    return if (path.startsWith("glb://")) path else "glb://$path"
}
