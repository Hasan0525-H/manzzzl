package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

internal object RoomBoundarySupportEvaluator {
    data class RoomIssue(
        val roomId: String,
        val boundarySupport: Float,
        val weakestEdgeSupport: Float,
        val weakestEdgeIndex: Int,
    )

    fun findUnsupportedRooms(plan: FloorPlan, rooms: List<RoomRegion>): List<RoomIssue> =
        rooms.mapNotNull { evaluateRoom(plan, it) }

    private fun evaluateRoom(plan: FloorPlan, room: RoomRegion): RoomIssue? {
        if (room.polygon.size < 3) return RoomIssue(room.id, 0f, 0f, 0)
        var total = 0f
        var supported = 0f
        var weakest = 1f
        var weakestIndex = -1
        room.polygon.indices.forEach { i ->
            val a = room.polygon[i]
            val b = room.polygon[(i + 1) % room.polygon.size]
            val length = distance(a, b)
            if (length < MIN_EDGE) return@forEach
            val value = edgeSupport(plan, a, b, length)
            total += length
            supported += value * length
            if (value < weakest) {
                weakest = value
                weakestIndex = i
            }
        }
        if (total == 0f) return RoomIssue(room.id, 0f, 0f, 0)
        val score = supported / total
        return if (score >= ROOM_THRESHOLD && weakest >= EDGE_THRESHOLD) null
        else RoomIssue(room.id, score, weakest, weakestIndex)
    }

    private fun edgeSupport(plan: FloorPlan, a: Vec2, b: Vec2, length: Float): Float {
        val ux = (b.x - a.x) / length
        val uz = (b.z - a.z) / length
        val count = max(5, ceil(length / 0.22f).toInt() + 1)
        var ok = 0
        repeat(count) { index ->
            val t = index / (count - 1f)
            val p = Vec2(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)
            if (wallSupports(plan, p, ux, uz) || openingSupports(plan, p, ux, uz)) ok++
        }
        return ok / count.toFloat()
    }

    private fun wallSupports(plan: FloorPlan, p: Vec2, ux: Float, uz: Float): Boolean =
        plan.walls.any { wall ->
            if (wall.confidence < 0.62f) return@any false
            val dx = wall.end.x - wall.start.x
            val dz = wall.end.z - wall.start.z
            val len = sqrt(dx * dx + dz * dz)
            if (len < MIN_EDGE) return@any false
            abs(ux * dx / len + uz * dz / len) >= 0.92f &&
                pointDistance(p, wall.start, wall.end) <= wallTolerance(wall.thicknessMeters)
        }

    private fun openingSupports(plan: FloorPlan, p: Vec2, ux: Float, uz: Float): Boolean =
        (plan.doors + plan.windows).any { opening ->
            if (opening is com.manzl.app.model.DoorOpening && opening.evidenceKind == DoorEvidenceKind.MEASURED_GAP) {
                return@any false
            }
            false
        }

    private fun wallTolerance(thickness: Float): Float = thickness.coerceIn(0.06f, 0.60f) / 2f + 0.11f

    private fun pointDistance(p: Vec2, a: Vec2, b: Vec2): Float {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val d = vx * vx + vz * vz
        if (d == 0f) return distance(p, a)
        val t = (((p.x - a.x) * vx + (p.z - a.z) * vz) / d).coerceIn(0f, 1f)
        return distance(p, Vec2(a.x + vx * t, a.z + vz * t))
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val x = a.x - b.x
        val z = a.z - b.z
        return sqrt(x * x + z * z)
    }

    private const val MIN_EDGE = 0.18f
    private const val ROOM_THRESHOLD = 0.86f
    private const val EDGE_THRESHOLD = 0.68f
}
