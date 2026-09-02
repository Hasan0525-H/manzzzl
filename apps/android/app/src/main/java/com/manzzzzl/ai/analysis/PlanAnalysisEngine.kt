package com.manzzzl.ai.analysis

import com.manzzzl.ai.model.HouseProject

/**
 * First analysis layer for converting a 2D plan into structured geometry.
 * The detection algorithms will be connected here later.
 */
class PlanAnalysisEngine {
    fun analyze(project: HouseProject): PlanAnalysisResult {
        return PlanAnalysisResult(
            projectId = project.id,
            wallsDetected = true,
            roomsDetected = true,
            openingsDetected = true
        )
    }
}
