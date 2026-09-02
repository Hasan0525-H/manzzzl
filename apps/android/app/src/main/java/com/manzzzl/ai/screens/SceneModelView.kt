package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.manzzzl.ai.model.ModelRenderer

/**
 * MVP 3D viewer boundary.
 * Keeps the screen ready for the real GLB renderer integration.
 */
@Composable
fun SceneModelView(modelPath: String) {
    val format = ModelRenderer().prepare(modelPath).format

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("نموذج المنزل ثلاثي الأبعاد")
        Text("الصيغة: $format")
        Text("المرحلة القادمة: تحميل GLB داخل محرك العرض")
    }
}
