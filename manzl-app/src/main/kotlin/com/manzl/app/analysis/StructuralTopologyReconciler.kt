package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Repairs small raster/CAD extraction defects before semantic inference and 3D extrusion.
 *
 * It performs only geometry-preserving operations:
 * - collapses duplicate/overlapping collinear runs;
 * - bridges tiny scan gaps that are too small to represent a usable architectural opening;
 * - snaps near-miss T/corner endpoints to perpendicular intersections;
 * - preserves door/window-sized gaps for later opening inference.
 */
internal object StructuralTopologyReconciler {

    fun reconcile(walls: List<WallSegment>): List<WallSegment> {
        if (walls.isEmpty()) return emptyList()

        val horizontal = mergeCollinear(
            walls.mapNotNull { wall -> AxisWall.from(wall, horizontal = true) },
        ).toMutableList()
        val vertical = mergeCollinear(
            walls.mapNotNull { wall -> AxisWall.from(wall, horizontal = false) },
        ).toMutableList()

        snapPerpendicularIntersections(horizontal, vertical)

        return (horizontal + vertical)
            .map { it.toWallSegment() }
            .filter { wall -> wall.lengthSquared() >= MIN_OUTPUT_WALL_METERS * MIN_OUTPUT_WALL_METERS }
            .sortedWith(
                compareBy<WallSegment> { min(it.start.z, it.end.z) }
                    .thenBy { min(it.start.x, it.end.x) }
                    .thenBy { max(it.start.z, it.end.z) }
                    .thenBy { max(it.start.x, it.end.x) }
            )
    }

    private fun mergeCollinear(source: List<AxisWall>): List<AxisWall> {
        if (source.isEmpty()) return emptyList()
        val groups = source.groupBy { quantize(it.fixed, LINE_BUCKET_METERS) }
        val result = ArrayList<AxisWall>()

        for (group in groups.values) {
            val fixed = weightedAverageFixed(group)
            val sorted = group.sortedBy { it.from }
            var current = sorted.first().copy(fixed = fixed)

            for (index in 1 until sorted.size) {
                val next = sorted[index].copy(fixed = fixed)
                val gap = next.from - current.to
                if (gap <= MAX_REPAIRABLE_COLLINEAR_GAP_METERS) {
                    val currentLength = (current.to - current.from).coerceAtLeast(0.01f)
                    val nextLength = (next.to - next.from).coerceAtLeast(0.01f)
                    val total = currentLength + nextLength
                    current = current.copy(
                        from = min(current.from, next.from),
                        to = max(current.to, next.to),
                        thickness = (
                            current.thickness * currentLength + next.thickness * nextLength
                            ) / total,
                        height = max(current.height, next.height),
                        confidence = max(current.confidence, next.confidence),
                    )
                } else {
                    result += current
                    current = next
                }
            }
            result += current
        }

        return result.sortedWith(compareBy<AxisWall> { it.fixed }.thenBy { it.from })
    }

    private fun snapPerpendicularIntersections(
        horizontal: MutableList<AxisWall>,
        vertical: MutableList<AxisWall>,
    ) {
        for (hIndex in horizontal.indices) {
            for (vIndex in vertical.indices) {
                var h = horizontal[hIndex]
                var v = vertical[vIndex]
                val intersectionX = v.fixed
                val intersectionZ = h.fixed

                if (intersectionX !in (h.from - ENDPOINT_SNAP_METERS)..(h.to + ENDPOINT_SNAP_METERS)) continue
                if (intersectionZ !in (v.from - ENDPOINT_SNAP_METERS)..(v.to + ENDPOINT_SNAP_METERS)) continue

                val hFromDistance = abs(intersectionX - h.from)
                val hToDistance = abs(intersectionX - h.to)
                val vFromDistance = abs(intersectionZ - v.from)
                val vToDistance = abs(intersectionZ - v.to)

                if (hFromDistance <= ENDPOINT_SNAP_METERS) h = h.copy(from = intersectionX)
                if (hToDistance <= ENDPOINT_SNAP_METERS) h = h.copy(to = intersectionX)
                if (vFromDistance <= ENDPOINT_SNAP_METERS) v = v.copy(from = intersectionZ)
                if (vToDistance <= ENDPOINT_SNAP_METERS) v = v.copy(to = intersectionZ)

                horizontal[hIndex] = h.normalized()
                vertical[vIndex] = v.normalized()
            }
        }
    }

    private fun weightedAverageFixed(group: List<AxisWall>): Float {
        var weighted = 0f
        var total = 0f
        for (wall in group) {
            val weight = (wall.to - wall.from).coerceAtLeast(0.05f)
            weighted += wall.fixed * weight
            total += weight
        }
        return if (total > 0f) weighted / total else group.first().fixed
    }

    private fun quantize(value: Float, step: Float): Int = round(value / step).toInt()

    private fun WallSegment.lengthSquared(): Float {
        val dx = end.x - start.x
        val dz = end.z - start.z
        return dx * dx + dz * dz
    }

    private data class AxisWall(
        val horizontal: Boolean,
        val fixed: Float,
        val from: Float,
        val to: Float,
        val thickness: Float,
        val height: Float,
        val confidence: Float,
    ) {
        fun normalized(): AxisWall = if (from <= to) this else copy(from = to, to = from)

        fun toWallSegment(): WallSegment = if (horizontal) {
            WallSegment(
                start = Vec2(from, fixed),
                end = Vec2(to, fixed),
                thicknessMeters = thickness,
                heightMeters = height,
                confidence = confidence,
            )
        } else {
            WallSegment(
                start = Vec2(fixed, from),
                end = Vec2(fixed, to),
                thicknessMeters = thickness,
                heightMeters = height,
                confidence = confidence,
            )
        }

        companion object {
            fun from(wall: WallSegment, horizontal: Boolean): AxisWall? {
                val dx = abs(wall.end.x - wall.start.x)
                val dz = abs(wall.end.z - wall.start.z)
                if (horizontal) {
                    if (dx < MIN_AXIS_CONTEXT_METERS || dz > AXIS_TOLERANCE_METERS) return null
                    return AxisWall(
                        horizontal = true,
                        fixed = (wall.start.z + wall.end.z) * 0.5f,
                        from = min(wall.start.x, wall.end.x),
                        to = max(wall.start.x, wall.end.x),
                        thickness = wall.thicknessMeters,
                        height = wall.heightMeters,
                        confidence = wall.confidence,
                    )
                }

                if (dz < MIN_AXIS_CONTEXT_METERS || dx > AXIS_TOLERANCE_METERS) return null
                return AxisWall(
                    horizontal = false,
                    fixed = (wall.start.x + wall.end.x) * 0.5f,
                    from = min(wall.start.z, wall.end.z),
                    to = max(wall.start.z, wall.end.z),
                    thickness = wall.thicknessMeters,
                    height = wall.heightMeters,
                    confidence = wall.confidence,
                )
            }
        }
    }

    private const val AXIS_TOLERANCE_METERS = 0.08f
    private const val MIN_AXIS_CONTEXT_METERS = 0.28f
    private const val LINE_BUCKET_METERS = 0.12f
    private const val MAX_REPAIRABLE_COLLINEAR_GAP_METERS = 0.26f
    private const val ENDPOINT_SNAP_METERS = 0.22f
    private const val MIN_OUTPUT_WALL_METERS = 0.34f
}
