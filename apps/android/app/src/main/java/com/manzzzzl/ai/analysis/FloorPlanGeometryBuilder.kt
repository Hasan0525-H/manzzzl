package com.manzzzl.ai.analysis

import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * Converts analyzed floor plan elements into 3D-ready geometry data.
 * Keeps geometry creation separate from Saudi visual styling.
 */
object FloorPlanGeometryBuilder {
    fun build(
        elements: FloorPlanElements,
        scale: Float
    ): ThreeDModel {
        // Geometry mapping layer prepared for the real vision parser output.
        // Walls, rooms and openings will be populated from analyzed elements.
        return ThreeDModel(
            walls = elements.walls,
            rooms = elements.rooms,
            openings = elements.openings
        )
    }
}

/**
 * Intermediate representation from floor plan analysis.
 */
data class FloorPlanElements(
    val walls: List<com.manzzzl.ai.analysis.model.Wall> = emptyList(),
    val rooms: List<com.manzzzl.ai.analysis.model.Room> = emptyList(),
    val openings: List<com.manzzzl.ai.analysis.model.Opening> = emptyList()
)
