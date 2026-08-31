package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Conservative interior/exterior wall-side classifier.
 *
 * Classification is side-aware: a wall can face conditioned interior on one side and outside,
 * courtyard or another open-air void on the other. The result is intended for façade materials
 * only; it never changes topology, collision or opening geometry.
 *
 * Primary evidence is trusted room polygons. When room coverage is incomplete, only walls close to
 * the measured plan envelope get a low-confidence fallback classification. Ambiguous internal walls
 * remain unclassified rather than receiving exterior finishes by guesswork.
 */
internal object ExteriorWallClassifier {

    fun classify(plan: FloorPlan): List<WallExposure> {
        if (plan.walls.isEmpty()) return emptyList()
        val indoorRooms = plan.rooms.filter(::isTrustedIndoorRoom)
        return plan.walls.mapIndexedNotNull { index, wall ->
            classifyWall(index, wall, plan, indoorRooms)
        }
    }

    private fun classifyWall(
        wallIndex: Int,
        wall: WallSegment,
        plan: FloorPlan,
        indoorRooms: List<RoomRegion>,
    ): WallExposure? {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < MIN_WALL_LENGTH_METERS) return null

        val axisX = dx / length
        val axisZ = dz / length
        val normalX = -axisZ
        val normalZ = axisX
        val midpoint = Vec2(
            x = (wall.start.x + wall.end.x) * 0.5f,
            z = (wall.start.z + wall.end.z) * 0.5f,
        )
        val probeDistance = maxOf(
            MIN_PROBE_DISTANCE_METERS,
            wall.thicknessMeters * 0.5f + PROBE_CLEARANCE_METERS,
        )
        val positiveProbe = Vec2(
            x = midpoint.x + normalX * probeDistance,
            z = midpoint.z + normalZ * probeDistance,
        )
        val negativeProbe = Vec2(
            x = midpoint.x - normalX * probeDistance,
            z = midpoint.z - normalZ * probeDistance,
        )

        val positiveIndoor = indoorRooms.any { pointInsidePolygon(positiveProbe, it.polygon) }
        val negativeIndoor = indoorRooms.any { pointInsidePolygon(negativeProbe, it.polygon) }

        if (positiveIndoor.xor(negativeIndoor)) {
            return WallExposure(
                wallIndex = wallIndex,
                positiveNormalExterior = !positiveIndoor,
                negativeNormalExterior = !negativeIndoor,
                confidence = ROOM_SIDE_CONFIDENCE,
                evidence = ExteriorEvidence.ROOM_SIDE,
            )
        }

        // If both sides are known interior, this is an internal partition.
        if (positiveIndoor && negativeIndoor) return null

        // No room-side evidence. Only walls close to the measured content envelope are allowed a
        // conservative fallback. Pick the normal that points farther away from the plan centre.
        if (!isNearPlanEnvelope(midpoint, wall, plan)) return null
        val positiveDistanceSq = positiveProbe.x * positiveProbe.x + positiveProbe.z * positiveProbe.z
        val negativeDistanceSq = negativeProbe.x * negativeProbe.x + negativeProbe.z * negativeProbe.z
        val positiveExterior = positiveDistanceSq > negativeDistanceSq + ENVELOPE_DIRECTION_EPSILON
        val negativeExterior = negativeDistanceSq > positiveDistanceSq + ENVELOPE_DIRECTION_EPSILON
        if (!positiveExterior && !negativeExterior) return null

        return WallExposure(
            wallIndex = wallIndex,
            positiveNormalExterior = positiveExterior,
            negativeNormalExterior = negativeExterior,
            confidence = ENVELOPE_FALLBACK_CONFIDENCE,
            evidence = ExteriorEvidence.PLAN_ENVELOPE,
        )
    }

    private fun isTrustedIndoorRoom(room: RoomRegion): Boolean =
        room.confidence >= MIN_ROOM_CONFIDENCE && !OpenAirRoomPolicy.shouldRemainOpenToSky(room)

    private fun isNearPlanEnvelope(midpoint: Vec2, wall: WallSegment, plan: FloorPlan): Boolean {
        val halfWidth = plan.widthMeters * 0.5f
        val halfDepth = plan.depthMeters * 0.5f
        if (halfWidth <= 0f || halfDepth <= 0f) return false
        val horizontal = abs(wall.end.x - wall.start.x) >= abs(wall.end.z - wall.start.z)
        return if (horizontal) {
            halfDepth - abs(midpoint.z) <= ENVELOPE_BAND_METERS
        } else {
            halfWidth - abs(midpoint.x) <= ENVELOPE_BAND_METERS
        }
    }

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (abs(denominator) < 0.000001f) 0.000001f else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private const val MIN_WALL_LENGTH_METERS = 0.30f
    private const val MIN_ROOM_CONFIDENCE = 0.66f
    private const val MIN_PROBE_DISTANCE_METERS = 0.16f
    private const val PROBE_CLEARANCE_METERS = 0.08f
    private const val ENVELOPE_BAND_METERS = 0.42f
    private const val ENVELOPE_DIRECTION_EPSILON = 0.0005f
    private const val ROOM_SIDE_CONFIDENCE = 0.91f
    private const val ENVELOPE_FALLBACK_CONFIDENCE = 0.58f
}

internal data class WallExposure(
    val wallIndex: Int,
    val positiveNormalExterior: Boolean,
    val negativeNormalExterior: Boolean,
    val confidence: Float,
    val evidence: ExteriorEvidence,
)

internal enum class ExteriorEvidence {
    ROOM_SIDE,
    PLAN_ENVELOPE,
}
