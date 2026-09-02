package com.manzzzl.ai.pipeline

import com.manzzzl.ai.analysis.PlanAnalyzer
import com.manzzzl.ai.model.HouseProject
import com.manzzzl.ai.model.FloorPlanAnalysis
import com.manzzzl.ai.analysis.GeometryGenerator

/**
 * Coordinates the complete project generation flow.
 * Keeps upload -> analysis -> geometry generation in one place.
 */
class ProjectPipeline(
    private val analyzer: PlanAnalyzer = PlanAnalyzer(),
    private val geometryGenerator: GeometryGenerator = GeometryGenerator()
) {
    data class Result(
        val analysis: FloorPlanAnalysis,
        val geometry: Any
    )

    fun generate(project: HouseProject): Result {
        val planPath = project.floorPlanPath
            ?: error("Floor plan is required")

        val analysis = analyzer.analyze(planPath)
        val geometry = geometryGenerator.generate(analysis)

        return Result(
            analysis = analysis,
            geometry = geometry
        )
    }
}
