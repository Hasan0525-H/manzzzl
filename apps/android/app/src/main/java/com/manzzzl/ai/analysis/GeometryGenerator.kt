package com.manzzzl.ai.analysis

import com.manzzzl.ai.model.FloorPlanAnalysis

/**
 * Converts analyzed floor plans into a 3D generation request.
 * Rendering engine can be connected later without changing the pipeline.
 */
class GeometryGenerator {
    fun generate(analysis: FloorPlanAnalysis): GeneratedGeometry {
        return GeneratedGeometry(
            sourcePath = analysis.sourcePath,
            roomCount = analysis.rooms.size,
            wallCount = analysis.walls.size
        )
    }
}

data class GeneratedGeometry(
    val sourcePath: String,
    val roomCount: Int,
    val wallCount: Int
)
