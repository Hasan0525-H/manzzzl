package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.StairLevelLink
import com.manzl.app.model.Staircase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministically links staircase evidence between adjacent uploaded floor plans.
 *
 * A link is metadata only: it never shifts a wall, stair or room. Matching is intentionally
 * fail-closed and requires spatial, axis and size agreement so a visually similar staircase on a
 * different part of the building cannot silently connect two levels.
 */
internal object StairLevelLinker {

    fun link(building: BuildingPlan): BuildingPlan {
        if (building.levels.size < 2) return building.copy(stairLinks = emptyList())

        val levels = building.levels.sortedBy { it.levelIndex }
        val links = ArrayList<StairLevelLink>()

        for (index in 0 until levels.lastIndex) {
            links += linkAdjacent(levels[index], levels[index + 1])
        }

        return building.copy(stairLinks = links)
    }

    private fun linkAdjacent(lower: FloorLevel, upper: FloorLevel): List<StairLevelLink> {
        val lowerStairs = lower.plan.stairs.withIndex()
            .filter { it.value.confidence >= MIN_STAIR_CONFIDENCE }
        val upperStairs = upper.plan.stairs.withIndex()
            .filter { it.value.confidence >= MIN_STAIR_CONFIDENCE }
        if (lowerStairs.isEmpty() || upperStairs.isEmpty()) return emptyList()

        val candidates = buildList {
            for (lowerEntry in lowerStairs) {
                for (upperEntry in upperStairs) {
                    scoreCandidate(lowerEntry.value, upperEntry.value)?.let { score ->
                        add(
                            Candidate(
                                lowerIndex = lowerEntry.index,
                                upperIndex = upperEntry.index,
                                score = score,
                            )
                        )
                    }
                }
            }
        }.sortedByDescending { it.score }

        val usedLower = HashSet<Int>()
        val usedUpper = HashSet<Int>()
        val result = ArrayList<StairLevelLink>()

        for (candidate in candidates) {
            if (candidate.score < MIN_LINK_CONFIDENCE) continue
            if (candidate.lowerIndex in usedLower || candidate.upperIndex in usedUpper) continue

            usedLower += candidate.lowerIndex
            usedUpper += candidate.upperIndex
            result += StairLevelLink(
                lowerLevelId = lower.id,
                upperLevelId = upper.id,
                lowerStairIndex = candidate.lowerIndex,
                upperStairIndex = candidate.upperIndex,
                confidence = candidate.score,
            )
        }

        return result
    }

    private fun scoreCandidate(lower: Staircase, upper: Staircase): Float? {
        val dx = lower.center.x - upper.center.x
        val dz = lower.center.z - upper.center.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance > MAX_CENTER_DISTANCE_METERS) return null

        val axisDelta = axisAngleDelta(lower.rotationDegrees, upper.rotationDegrees)
        if (axisDelta > MAX_AXIS_DELTA_DEGREES) return null

        val widthRatio = ratio(lower.widthMeters, upper.widthMeters)
        if (widthRatio < MIN_WIDTH_RATIO) return null
        val runRatio = ratio(lower.runMeters, upper.runMeters)
        if (runRatio < MIN_RUN_RATIO) return null

        val heightDelta = abs(
            (lower.floorToFloorHeightMeters + 0f) -
                (upper.floorToFloorHeightMeters + 0f)
        )
        if (heightDelta > MAX_FLOOR_HEIGHT_DELTA_METERS) return null

        val distanceScore = (1f - distance / MAX_CENTER_DISTANCE_METERS).coerceIn(0f, 1f)
        val axisScore = (1f - axisDelta / MAX_AXIS_DELTA_DEGREES).coerceIn(0f, 1f)
        val widthScore = ((widthRatio - MIN_WIDTH_RATIO) / (1f - MIN_WIDTH_RATIO)).coerceIn(0f, 1f)
        val runScore = ((runRatio - MIN_RUN_RATIO) / (1f - MIN_RUN_RATIO)).coerceIn(0f, 1f)
        val evidenceScore = min(lower.confidence, upper.confidence).coerceIn(0f, 1f)
        val floorHeightScore = (1f - heightDelta / MAX_FLOOR_HEIGHT_DELTA_METERS).coerceIn(0f, 1f)

        return (
            distanceScore * 0.36f +
                axisScore * 0.19f +
                widthScore * 0.13f +
                runScore * 0.10f +
                floorHeightScore * 0.08f +
                evidenceScore * 0.14f
            ).coerceIn(0f, 1f)
    }

    private fun ratio(a: Float, b: Float): Float {
        val high = max(a, b)
        val low = min(a, b)
        return if (high <= 0.0001f) 0f else low / high
    }

    /** Stair direction may reverse by 180° between drawings while still representing one shaft. */
    private fun axisAngleDelta(a: Float, b: Float): Float {
        val normalizedA = normalize180(a)
        val normalizedB = normalize180(b)
        val raw = abs(normalizedA - normalizedB)
        return min(raw, 180f - raw)
    }

    private fun normalize180(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private data class Candidate(
        val lowerIndex: Int,
        val upperIndex: Int,
        val score: Float,
    )

    private const val MIN_STAIR_CONFIDENCE = 0.66f
    private const val MAX_CENTER_DISTANCE_METERS = 1.80f
    private const val MAX_AXIS_DELTA_DEGREES = 22f
    private const val MIN_WIDTH_RATIO = 0.68f
    private const val MIN_RUN_RATIO = 0.58f
    private const val MAX_FLOOR_HEIGHT_DELTA_METERS = 0.55f
    private const val MIN_LINK_CONFIDENCE = 0.58f
}
