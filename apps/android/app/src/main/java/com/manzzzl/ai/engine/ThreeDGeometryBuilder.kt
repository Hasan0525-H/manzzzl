package com.manzzzl.ai.engine

import com.manzzzl.ai.data.PlanAnalysisResult

/**
 * First stage 3D reconstruction layer.
 * Converts analyzed 2D plan data into geometry instructions.
 * Furniture is intentionally excluded.
 */
class ThreeDGeometryBuilder {

    fun build(plan: PlanAnalysisResult): ThreeDModel {
        return ThreeDModel(
            walls = plan.walls,
            rooms = plan.rooms,
            openings = plan.openings
        )
    }
}

 data class ThreeDModel(
    val walls: List<Any>,
    val rooms: List<Any>,
    val openings: List<Any>
)
