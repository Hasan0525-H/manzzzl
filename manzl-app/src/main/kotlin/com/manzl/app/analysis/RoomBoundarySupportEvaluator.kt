package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Verifies that every trusted room polygon is actually backed by measured wall/opening geometry.
 *
 * A room polygon can look plausible and still create a visibly wrong house when one of its sides was
 * inferred through open space. This evaluator samples every polygon edge in metric coordinates and
 * requires support from a parallel measured wall face or a classified door/window opening. It never
 * changes topology; it only identifies room boundaries that are unsafe to extrude into floors or
 * ceilings.
 */
internal object RoomBoundarySupportEvaluator {

    data class RoomIssue(
        val roomId: String,
        val boundarySupport: Float,
        val weakestEdgeSupport: Float,
        val weakestEdgeIndex: Int,
    )

    fun findUnsupportedRooms(
        plan: FloorPlan,
        rooms: List<RoomRegion>,
    ): List<RoomIssue> = rooms.mapNotNull { room -> evaluateRoom(plan, room) }

    private fun evaluateRoom(plan: FloorPlan, room: RoomRegion): RoomIssue? {
        if (room.polygon.size < 3) {
            return RoomIssue(room.id, 0f, 0f, 0)
        }

        var weightedSupport = 0f
        var totalLength = 0f
        var weakestSupport = 1f
        var weakestIndex = -1

        for (edgeIndex in room.polygon.indices) {
            val a = room.polygon[edgeIndex]
            val b = room.polygon[(edgeIndex + 1) % room.polygon.size]
            val length = distance(a, b)
            if (length < MIN_EDGE_METERS) continue

            val support = edgeSupport(plan, a, b, length)
            weightedSupport += support * length
            totalLength += length
            if (support < weakestSupport) {
                weakestSupport = support
                weakestIndex = edgeIndex
            }
        }

        if (totalLength <= EPSILON || weakestIndex < 0) {
            return RoomIssue(room.id, 0f, 0f, 0)
        }

        val boundarySupport = (weightedSupport / totalLength).coerceIn(0f, 1f)
        return if (
            boundarySupport >= MIN_ROOM_BOUNDARY_SUPPORT &&
            weakestSupport >= MIN_SINGLE_EDGE_SUPPORT
        ) {
            null
        } else {
            RoomIssue(
                roomId = room.id,
                boundarySupport = boundarySupport,
                weakestEdgeSupport = weakestSupport,
                weakestEdgeIndex = weakestIndex,
            )
        }
    }

    private fun edgeSupport(
        plan: FloorPlan,
        a: Vec2,
        b: Vec2,
        length: Float,
    ): Float {
        val ux = (b.x - a.x) / length
        val uz = (b.z - a.z) / length
        val sampleCount = max(
            MIN_SAMPLES_PER_EDGE,
            ceil(length / SAMPLE_SPACING_METERS).toInt() + 1,
        )
        var supported = 0
        for (index in 0 until sampleCount) {
            // Keep probes slightly away from polygon vertices. Junction snapping errors should not
            // make an otherwise measured edge fail, while a missing mid-edge wall remains obvious.
            val rawT = if (sampleCount == 1) 0.5f else index / (sampleCount - 1f)
            val t = EDGE_SAMPLE_INSET_FRACTION + rawT * (1f - EDGE_SAMPLE_INSET_FRACTION * 2f)
            val point = Vec2(
                x = a.x + (b.x - a.x) * t,
                z = a.z + (b.z - a.z) * t,
            )
            if (supportedByWall(plan, point, ux, uz) || supportedByOpening(plan, point, ux, uz)) {
                supported++
            }
        }
        return supported / sampleCount.toFloat()
    }

    private fun supportedByWall(
        plan: FloorPlan,
        point: Vec2,
        edgeUx: Float,
        edgeUz: Float,
    ): Boolean = plan.walls.any { wall ->
        if (wall.confidence < MIN_WALL_CONFIDENCE) return@any false
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < MIN_EDGE_METERS) return@any false
        val alignment = abs(edgeUx * (dx / length) + edgeUz * (dz / length))
        if (alignment < MIN_AXIS_ALIGNMENT) return@any false
        val tolerance = wall.thicknessMeters.coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS) * 0.5f +
            WALL_CENTERLINE_TOLERANCE_METERS
        pointSegmentDistance(point, wall.start, wall.end) <= tolerance
    }

    private fun supportedByOpening(
        plan: FloorPlan,
        point: Vec2,
        edgeUx: Float,
        edgeUz: Float,
    ): Boolean {
        val door = plan.doors.any { opening ->
            opening.evidenceKind != DoorEvidenceKind.MEASURED_GAP &&
                opening.confidence >= MIN_OPENING_CONFIDENCE &&
                openingSupports(
                    point = point,
                    edgeUx = edgeUx,
                    edgeUz = edgeUz,
                    center = opening.center,
                    widthMeters = opening.widthMeters,
                    rotationDegrees = opening.rotationDegrees,
                )
        }
        if (door) return true
        return plan.windows.any { opening ->
            opening.confidence >= MIN_OPENING_CONFIDENCE &&
                openingSupports(
                    point = point,
                    edgeUx = edgeUx,
                    edgeUz = edgeUz,
                    center = opening.center,
                    widthMeters = opening.widthMeters,
                    rotationDegrees = opening.rotationDegrees,
                )
        }
    }

    private fun openingSupports(
        point: Vec2,
        edgeUx: Float,
        edgeUz: Float,
        center: Vec2,
        widthMeters: Float,
        rotationDegrees: Float,
    ): Boolean {
        if (widthMeters <= 0f) return false
        val radians = rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        if (abs(edgeUx * ux + edgeUz * uz) < MIN_AXIS_ALIGNMENT) return false
        val half = widthMeters * 0.5f
        val start = Vec2(center.x - ux * half, center.z - uz * half)
        val end = Vec2(center.x + ux * half, center.z + uz * half)
        return pointSegmentDistance(point, start, end) <= OPENING_AXIS_TOLERANCE_METERS
    }

    private fun pointSegmentDistance(point: Vec2, a: Vec2, b: Vec2): Float {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= EPSILON) return distance(point, a)
        val t = (((point.x - a.x) * vx + (point.z - a.z) * vz) / lengthSq).coerceIn(0f, 1f)
        val dx = point.x - (a.x + vx * t)
        val dz = point.z - (a.z + vz * t)
        return sqrt(dx * dx + dz * dz)
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    private const val MIN_EDGE_METERS = 0.18f
    private const val SAMPLE_SPACING_METERS = 0.22f
    private const val MIN_SAMPLES_PER_EDGE = 5
    private const val EDGE_SAMPLE_INSET_FRACTION = 0.035f
    private const val MIN_WALL_CONFIDENCE = 0.62f
    private const val MIN_OPENING_CONFIDENCE = 0.66f
    private const val MIN_WALL_THICKNESS_METERS = 0.06f
    private const val MAX_WALL_THICKNESS_METERS = 0.60f
    private const val WALL_CENTERLINE_TOLERANCE_METERS = 0.11f
    private const val OPENING_AXIS_TOLERANCE_METERS = 0.13f
    private const val MIN_ROOM_BOUNDARY_SUPPORT = 0.86f
    private const val MIN_SINGLE_EDGE_SUPPORT = 0.68f
    private val MIN_AXIS_ALIGNMENT = cos(12.0 * PI / 180.0).toFloat()
    private const val EPSILON = 0.000001f
}
