package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Placeholder boundary for the real 3D engine.
 * Keeps the screen ready for GLB renderer integration.
 */
@Composable
fun SceneModelView(modelPath: String) {
    Text("تحميل نموذج 3D: $modelPath")
    Text("تحكم الكاميرا: دوران - تكبير - استكشاف")
}
