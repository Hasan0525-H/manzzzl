package com.manzzzl.ai.analysis

import com.manzzzl.ai.analysis.model.Opening
import com.manzzzl.ai.analysis.model.Room
import com.manzzzl.ai.analysis.model.Wall

/**
 * Extracts structural elements from a processed 2D floor plan.
 * The image vision implementation can be connected later without changing 3D generation.
 */
object FloorPlanElementExtractor {
    fun extract(
        floorPlanData: String
    ): FloorPlanElements {
        // Initial pipeline contract. Vision model output will populate these lists.
        return FloorPlanElements(
            walls = emptyList(),
            rooms = emptyList(),
            openings = emptyList()
        )
    }
}

data class FloorPlanElements(
    val walls: List<Wall>,
    val rooms: List<Room>,
    val openings: List<Opening>
)
