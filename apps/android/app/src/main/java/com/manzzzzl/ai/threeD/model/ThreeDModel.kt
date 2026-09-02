package com.manzzzl.ai.threeD.model

import com.manzzzl.ai.analysis.model.Room
import com.manzzzl.ai.analysis.model.Wall
import com.manzzzl.ai.analysis.model.Opening

/**
 * Unified representation prepared for the 3D viewer.
 * Furniture is intentionally excluded.
 */
data class ThreeDModel(
    val walls: List<Wall> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val openings: List<Opening> = emptyList()
)
