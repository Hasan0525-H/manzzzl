package com.manzzzl.ai.threeD

import com.manzzzl.ai.analysis.model.Opening
import com.manzzzl.ai.analysis.model.Room
import com.manzzzl.ai.analysis.model.Wall
import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * Converts extracted floor plan elements into the 3D model structure.
 * Geometry stays independent from Saudi visual styling.
 */
object FloorPlanTo3DMapper {
    fun map(
        walls: List<Wall>,
        rooms: List<Room>,
        openings: List<Opening>
    ): ThreeDModel {
        return ThreeDModel(
            walls = walls,
            rooms = rooms,
            openings = openings
        )
    }
}
