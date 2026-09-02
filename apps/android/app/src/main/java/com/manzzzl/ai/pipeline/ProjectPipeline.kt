package com.manzzzl.ai.pipeline

import com.manzzzl.ai.analysis.GeneratedGeometry
import com.manzzzl.ai.analysis.GeometryGenerator
import com.manzzzl.ai.analysis.PlanAnalyzer
import com.manzzzl.ai.model.FloorPlanAnalysis
import com.manzzzl.ai.model.HouseProject

/**
 * Single coordinator for upload -> analysis -> geometry generation.
 */
class ProjectPipeline(
    private val analyzer: PlanAnalyzer = PlanAnalyzer(),
    private val geometryGenerator: GeometryGenerator = GeometryGenerator()
) {
    data class Result(
        val analysis: FloorPlanAnalysis,
        val geometry: GeneratedGeometry
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
