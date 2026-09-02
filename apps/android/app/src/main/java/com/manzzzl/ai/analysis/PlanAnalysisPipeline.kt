package com.manzzzl.ai.analysis

/**
 * Foundation for the free/open-source 2D floor plan analysis pipeline.
 *
 * Future stages:
 * - image preprocessing
 * - wall detection
 * - room extraction
 * - openings detection (doors/windows)
 * - 3D geometry generation
 */
class PlanAnalysisPipeline {

    fun analyze(planPath: String): PlanAnalysisResult {
        return PlanAnalysisResult(
            sourcePath = planPath,
            status = "READY_FOR_ANALYSIS"
        )
    }
}

data class PlanAnalysisResult(
    val sourcePath: String,
    val status: String
)
