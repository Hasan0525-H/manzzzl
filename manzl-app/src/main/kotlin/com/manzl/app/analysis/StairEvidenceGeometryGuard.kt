package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry-only guard for staircase observations.
 *
 * Repeated raster strokes are not sufficient by themselves: dimension hatching, cabinetry and title
 * blocks can also form parallel bands. A stair candidate therefore has to occupy plausible measured
 * free space. When trusted room polygons exist its centre and most footprint probes must belong to a
 * single room. Strong wall centre-lines are not allowed to cut through the core of the proposed run.
 *
 * This guard never creates or moves a stair. It can only reject semantic evidence before fusion.
 */
internal object StairEvidenceGeometryGuard {

    fun isPlausible(plan: FloorPlan, evidence: SemanticEvidence): Boolean {
        if (evidence.kind != SemanticKind.STAIR) return false
        val width = evidence.widthMeters ?: return false
        val run = evidence.lengthMeters ?: return false
        if (width !in MIN_WIDTH_METERS..MAX_WIDTH_METERS) return false
        if (run !in MIN_RUN_METERS..MAX_RUN_METERS) return false
        if (!insidePlan(plan, evidence.center, OUTER_MARGIN_METERS)) return false

        val footprint = footprintProbes(
            center = evidence.center,
            width = width,
            run = run,
            rotationDegrees = evidence.rotationDegrees ?: 0f,
        )
        if (footprint.any { !insidePlan(plan, it, 0f) }) return false

        val trustedRooms = plan.rooms.filter { room ->
            room.confidence >= MIN_ROOM_CONFIDENCE && room.polygon.size >= 3
        }
        if (trustedRooms.isNotEmpty()) {
            val host = trustedRooms
                .filter { pointInsidePolygon(evidence.center, it.polygon) }
                .maxByOrNull { roomProbeSupport(it, footprint) }
                ?: return false
            if (roomProbeSupport(host, footprint) < MIN_ROOM_PROBE_SUPPORT) return false
        }

        val coreHalfRun = run * CORE_RUN_FRACTION * 0.5f
        val coreHalfWidth = width * CORE_WIDTH_FRACTION * 0.5f
        val core = orientedRectangle(
            center = evidence.center,
            halfRun = coreHalfRun,
            halfWidth = coreHalfWidth,
            rotationDegrees = evidence.rotationDegrees ?: 0f,
        )
        val crossingWalls = plan.walls.count { wall ->
            wall.confidence >= MIN_WALL_CONFIDENCE && wallCrossesPolygonInterior(wall, core)
        }
        return crossingWalls <= MAX_CORE_CROSSING_WALLS
    }

    private fun footprintProbes(
        center: Vec2,
        width: Float,
        run: Float,
        rotationDegrees: Float,
    ): List<Vec2> {
        val radians = rotationDegrees * PI.toFloat() / 180f
        val rx = cos(radians)
        val rz = sin(radians)
        val wx = -rz
        val wz = rx
        val halfRun = run * 0.5f
        val halfWidth = width * 0.5f
        val insetRun = (halfRun - PROBE_INSET_METERS).coerceAtLeast(halfRun * 0.72f)
        val insetWidth = (halfWidth - PROBE_INSET_METERS).coerceAtLeast(halfWidth * 0.68f)

        fun point(runOffset: Float, widthOffset: Float) = Vec2(
            x = center.x + rx * runOffset + wx * widthOffset,
            z = center.z + rz * runOffset + wz * widthOffset,
        )
        return listOf(
            center,
            point(-insetRun, -insetWidth),
            point(-insetRun, insetWidth),
            point(insetRun, -insetWidth),
            point(insetRun, insetWidth),
            point(-insetRun, 0f),
            point(insetRun, 0f),
            point(0f, -insetWidth),
            point(0f, insetWidth),
        )
    }

    private fun roomProbeSupport(room: RoomRegion, probes: List<Vec2>): Float {
        if (probes.isEmpty()) return 0f
        val supported = probes.count { pointInsidePolygon(it, room.polygon) }
        return supported / probes.size.toFloat()
    }

    private fun orientedRectangle(
        center: Vec2,
        halfRun: Float,
        halfWidth: Float,
        rotationDegrees: Float,
    ): List<Vec2> {
        val radians = rotationDegrees * PI.toFloat() / 180f
        val rx = cos(radians)
        val rz = sin(radians)
        val wx = -rz
        val wz = rx
        fun point(r: Float, w: Float) = Vec2(
            x = center.x + rx * r + wx * w,
            z = center.z + rz * r + wz * w,
        )
        return listOf(
            point(-halfRun, -halfWidth),
            point(halfRun, -halfWidth),
            point(halfRun, halfWidth),
            point(-halfRun, halfWidth),
        )
    }

    private fun wallCrossesPolygonInterior(wall: WallSegment, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        val midpoint = Vec2(
            x = (wall.start.x + wall.end.x) * 0.5f,
            z = (wall.start.z + wall.end.z) * 0.5f,
        )
        if (pointInsidePolygon(midpoint, polygon)) return true
        if (pointInsidePolygon(wall.start, polygon) || pointInsidePolygon(wall.end, polygon)) return true

        for (index in polygon.indices) {
            if (segmentsIntersect(wall.start, wall.end, polygon[index], polygon[(index + 1) % polygon.size])) {
                // A single touch can be a surrounding stair wall. Require another distinct boundary
                // crossing or a sampled interior point before treating it as a divider through core.
                val oneThird = interpolate(wall.start, wall.end, 1f / 3f)
                val twoThirds = interpolate(wall.start, wall.end, 2f / 3f)
                if (pointInsidePolygon(oneThird, polygon) || pointInsidePolygon(twoThirds, polygon)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Boolean {
        val arx = a1.x - a0.x
        val arz = a1.z - a0.z
        val brx = b1.x - b0.x
        val brz = b1.z - b0.z
        val denominator = arx * brz - arz * brx
        if (abs(denominator) <= EPSILON) return false
        val qx = b0.x - a0.x
        val qz = b0.z - a0.z
        val t = (qx * brz - qz * brx) / denominator
        val u = (qx * arz - qz * arx) / denominator
        return t in 0f..1f && u in 0f..1f
    }

    private fun interpolate(a: Vec2, b: Vec2, t: Float) = Vec2(
        x = a.x + (b.x - a.x) * t,
        z = a.z + (b.z - a.z) * t,
    )

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (abs(denominator) <= EPSILON) EPSILON else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun insidePlan(plan: FloorPlan, point: Vec2, margin: Float): Boolean {
        val halfWidth = (plan.widthMeters * 0.5f - margin).coerceAtLeast(0f)
        val halfDepth = (plan.depthMeters * 0.5f - margin).coerceAtLeast(0f)
        return point.x in -halfWidth..halfWidth && point.z in -halfDepth..halfDepth
    }

    private const val MIN_WIDTH_METERS = 0.70f
    private const val MAX_WIDTH_METERS = 2.80f
    private const val MIN_RUN_METERS = 1.45f
    private const val MAX_RUN_METERS = 9.00f
    private const val OUTER_MARGIN_METERS = 0.05f
    private const val PROBE_INSET_METERS = 0.08f
    private const val MIN_ROOM_CONFIDENCE = 0.66f
    private const val MIN_ROOM_PROBE_SUPPORT = 0.66f
    private const val MIN_WALL_CONFIDENCE = 0.68f
    private const val CORE_RUN_FRACTION = 0.72f
    private const val CORE_WIDTH_FRACTION = 0.60f
    private const val MAX_CORE_CROSSING_WALLS = 0
    private const val EPSILON = 0.000001f
}
