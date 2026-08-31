package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-first doorway inference for the offline pipeline.
 *
 * The classical wall detector emits axis-aligned wall runs. Real door openings often appear as
 * short gaps between two collinear runs. This class turns those gaps into explicit DoorOpening
 * metadata so collision/navigation and the future door-frame renderer share the same truth.
 *
 * It deliberately refuses to invent an opening when evidence is weak. A future local neural model
 * can contribute additional candidates, but this deterministic layer remains the topology guard.
 */
internal object DoorInferenceEngine {

    fun infer(plan: FloorPlan): List<DoorOpening> {
        val horizontal = plan.walls.filter(::isHorizontal)
        val vertical = plan.walls.filter(::isVertical)

        val candidates = buildList {
            addAll(inferHorizontal(horizontal))
            addAll(inferVertical(vertical))
        }

        return deduplicate(candidates)
    }

    private fun inferHorizontal(walls: List<WallSegment>): List<DoorOpening> {
        val groups = walls.groupBy { quantize((it.start.z + it.end.z) * 0.5f, LINE_BUCKET_METERS) }
        return buildList {
            for (group in groups.values) {
                val sorted = group.sortedBy { min(it.start.x, it.end.x) }
                for (index in 0 until sorted.lastIndex) {
                    val left = sorted[index]
                    val right = sorted[index + 1]
                    val leftEnd = max(left.start.x, left.end.x)
                    val rightStart = min(right.start.x, right.end.x)
                    val gap = rightStart - leftEnd
                    if (gap !in MIN_DOOR_WIDTH_METERS..MAX_DOOR_WIDTH_METERS) continue

                    val zA = (left.start.z + left.end.z) * 0.5f
                    val zB = (right.start.z + right.end.z) * 0.5f
                    if (abs(zA - zB) > COLLINEAR_TOLERANCE_METERS) continue

                    add(
                        DoorOpening(
                            center = Vec2((leftEnd + rightStart) * 0.5f, (zA + zB) * 0.5f),
                            widthMeters = gap,
                            rotationDegrees = 0f,
                            confidence = gapConfidence(gap),
                        )
                    )
                }
            }
        }
    }

    private fun inferVertical(walls: List<WallSegment>): List<DoorOpening> {
        val groups = walls.groupBy { quantize((it.start.x + it.end.x) * 0.5f, LINE_BUCKET_METERS) }
        return buildList {
            for (group in groups.values) {
                val sorted = group.sortedBy { min(it.start.z, it.end.z) }
                for (index in 0 until sorted.lastIndex) {
                    val top = sorted[index]
                    val bottom = sorted[index + 1]
                    val topEnd = max(top.start.z, top.end.z)
                    val bottomStart = min(bottom.start.z, bottom.end.z)
                    val gap = bottomStart - topEnd
                    if (gap !in MIN_DOOR_WIDTH_METERS..MAX_DOOR_WIDTH_METERS) continue

                    val xA = (top.start.x + top.end.x) * 0.5f
                    val xB = (bottom.start.x + bottom.end.x) * 0.5f
                    if (abs(xA - xB) > COLLINEAR_TOLERANCE_METERS) continue

                    add(
                        DoorOpening(
                            center = Vec2((xA + xB) * 0.5f, (topEnd + bottomStart) * 0.5f),
                            widthMeters = gap,
                            rotationDegrees = 90f,
                            confidence = gapConfidence(gap),
                        )
                    )
                }
            }
        }
    }

    private fun deduplicate(candidates: List<DoorOpening>): List<DoorOpening> {
        val result = ArrayList<DoorOpening>()
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            val duplicate = result.any { existing ->
                val dx = existing.center.x - candidate.center.x
                val dz = existing.center.z - candidate.center.z
                dx * dx + dz * dz <= DUPLICATE_RADIUS_METERS * DUPLICATE_RADIUS_METERS
            }
            if (!duplicate) result += candidate
        }
        return result
    }

    private fun isHorizontal(wall: WallSegment): Boolean {
        val dx = abs(wall.end.x - wall.start.x)
        val dz = abs(wall.end.z - wall.start.z)
        return dx >= MIN_WALL_FOR_DOOR_CONTEXT_METERS && dz <= AXIS_TOLERANCE_METERS
    }

    private fun isVertical(wall: WallSegment): Boolean {
        val dx = abs(wall.end.x - wall.start.x)
        val dz = abs(wall.end.z - wall.start.z)
        return dz >= MIN_WALL_FOR_DOOR_CONTEXT_METERS && dx <= AXIS_TOLERANCE_METERS
    }

    private fun gapConfidence(width: Float): Float {
        val delta = abs(width - IDEAL_DOOR_WIDTH_METERS)
        return (0.91f - delta * 0.20f).coerceIn(0.68f, 0.91f)
    }

    private fun quantize(value: Float, step: Float): Int = kotlin.math.round(value / step).toInt()

    private const val MIN_DOOR_WIDTH_METERS = 0.68f
    private const val MAX_DOOR_WIDTH_METERS = 1.45f
    private const val IDEAL_DOOR_WIDTH_METERS = 0.95f
    private const val MIN_WALL_FOR_DOOR_CONTEXT_METERS = 0.42f
    private const val AXIS_TOLERANCE_METERS = 0.07f
    private const val COLLINEAR_TOLERANCE_METERS = 0.16f
    private const val LINE_BUCKET_METERS = 0.14f
    private const val DUPLICATE_RADIUS_METERS = 0.33f
}
