package com.manzzzl.ai.threeD

import com.manzzzl.ai.analysis.model.Room
import com.manzzzl.ai.analysis.model.Wall

/**
 * Converts analysed 2D plan elements into a 3D-ready geometry structure.
 * Furniture is intentionally excluded; decoration remains a separate layer.
 */
class ThreeDGeometryBuilder {

    fun build(
        walls: List<Wall>,
        rooms: List<Room>
    ): ThreeDGeometry {
        return ThreeDGeometry(
            walls = walls,
            rooms = rooms
        )
    }
}

data class ThreeDGeometry(
    val walls: List<Wall>,
    val rooms: List<Room>
)
