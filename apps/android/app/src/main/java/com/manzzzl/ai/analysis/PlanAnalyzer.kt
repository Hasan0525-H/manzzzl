package com.manzzzl.ai.analysis

import com.manzzzl.ai.model.FloorPlanAnalysis

/**
 * First analysis pipeline abstraction for 2D plans.
 * Real ML inference can be connected here later.
 */
class PlanAnalyzer {
    fun analyze(imagePath: String): FloorPlanAnalysis {
        return FloorPlanAnalysis(
            sourcePath = imagePath,
            walls = emptyList(),
            rooms = emptyList(),
            doors = emptyList(),
            windows = emptyList()
        )
    }
}
