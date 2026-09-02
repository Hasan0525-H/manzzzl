package com.manzzzl.ai.model

/**
 * Rendering abstraction for generated house models.
 * A real GLB renderer can be plugged behind this interface.
 */
class ModelRenderer {
    fun canRender(modelPath: String?): Boolean {
        return !modelPath.isNullOrBlank() && modelPath.startsWith("glb://")
    }

    fun prepare(modelPath: String): RenderModel {
        return RenderModel(
            source = modelPath,
            ready = canRender(modelPath)
        )
    }
}

data class RenderModel(
    val source: String,
    val ready: Boolean
)
