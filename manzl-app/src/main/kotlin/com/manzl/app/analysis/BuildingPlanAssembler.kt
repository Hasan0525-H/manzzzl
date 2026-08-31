package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan

/**
 * Converts an ordered set of independently analysed floor drawings into one building model.
 *
 * The list order is authoritative: index 0 is the lowest uploaded floor. Base elevations are
 * metadata only and never translate X/Z geometry. When trustworthy staircase evidence is available
 * it provides the floor-to-floor height; otherwise a conservative residential fallback is used.
 */
internal object BuildingPlanAssembler {

    fun assemble(orderedPlans: List<FloorPlan>): BuildingPlan {
        require(orderedPlans.isNotEmpty()) { "At least one floor plan is required" }

        var elevation = 0f
        val levels = orderedPlans.mapIndexed { index, plan ->
            val level = FloorLevel(
                id = "level-$index",
                levelIndex = index,
                baseElevationMeters = elevation,
                plan = plan,
            )
            if (index < orderedPlans.lastIndex) {
                elevation += floorToFloorHeight(plan)
            }
            level
        }

        val building = BuildingPlan(levels = levels)
        return if (levels.size > 1) StairLevelLinker.link(building) else building
    }

    private fun floorToFloorHeight(plan: FloorPlan): Float {
        val candidates = plan.stairs
            .asSequence()
            .filter { it.confidence >= MIN_STAIR_CONFIDENCE }
            .map { it.floorToFloorHeightMeters }
            .filter { it in MIN_FLOOR_HEIGHT_METERS..MAX_FLOOR_HEIGHT_METERS }
            .sorted()
            .toList()

        if (candidates.isEmpty()) return DEFAULT_FLOOR_TO_FLOOR_METERS
        val middle = candidates.size / 2
        return if (candidates.size % 2 == 1) {
            candidates[middle]
        } else {
            (candidates[middle - 1] + candidates[middle]) * 0.5f
        }
    }

    private const val MIN_STAIR_CONFIDENCE = 0.66f
    private const val MIN_FLOOR_HEIGHT_METERS = 2.55f
    private const val MAX_FLOOR_HEIGHT_METERS = 4.80f
    private const val DEFAULT_FLOOR_TO_FLOOR_METERS = 3.20f
}
