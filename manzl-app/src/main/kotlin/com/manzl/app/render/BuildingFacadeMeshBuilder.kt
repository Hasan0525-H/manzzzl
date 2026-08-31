package com.manzl.app.render

import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.BuildingPlan

/** Builds one batched façade skin across all floors, preserving each floor's declared elevation. */
internal object BuildingFacadeMeshBuilder {

    fun build(building: BuildingPlan): FacadeMesh {
        var vertices = floatArrayOf()
        var indices = intArrayOf()
        for (level in building.levels.sortedBy { it.levelIndex }) {
            val design = ReferenceDrivenDesignEngine.synthesize(level.plan)
            val levelMesh = FacadeMeshBuilder.build(
                plan = level.plan,
                wallHeightOverride = design.wallHeightMeters,
                doorHeightOverride = design.doorHeightMeters,
            ).translatedY(level.baseElevationMeters)
            if (levelMesh.vertices.isEmpty()) continue
            val vertexOffset = vertices.size / FLOATS_PER_VERTEX
            vertices += levelMesh.vertices
            indices += IntArray(levelMesh.indices.size) { i -> levelMesh.indices[i] + vertexOffset }
        }
        return FacadeMesh(vertices, indices)
    }
}

private fun FacadeMesh.translatedY(offsetMeters: Float): FacadeMesh {
    if (offsetMeters == 0f || vertices.isEmpty()) return this
    val translated = vertices.copyOf()
    var index = 0
    while (index + FLOATS_PER_VERTEX <= translated.size) {
        translated[index + 1] += offsetMeters
        index += FLOATS_PER_VERTEX
    }
    return FacadeMesh(translated, indices)
}

private const val FLOATS_PER_VERTEX = 6
