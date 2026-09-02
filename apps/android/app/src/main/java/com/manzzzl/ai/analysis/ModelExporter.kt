package com.manzzzl.ai.analysis

/**
 * Converts generated geometry into a renderable model reference.
 *
 * The first implementation keeps the export contract isolated so a real
 * GLB/GLTF encoder can be plugged in without changing the pipeline.
 */
class ModelExporter {

    data class ExportedModel(
        val format: String,
        val modelId: String,
        val ready: Boolean
    )

    fun export(geometry: GeneratedGeometry): ExportedModel {
        return ExportedModel(
            format = "glb",
            modelId = "house-${geometry.roomCount}-${geometry.wallCount}",
            ready = true
        )
    }
}
