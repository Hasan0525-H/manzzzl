package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Chooses a first-person spawn from trusted room geometry instead of blindly using the drawing
 * bounding-box centre.
 *
 * This is deliberately conservative: only already-inferred interior room polygons participate,
 * every candidate must still pass CollisionWorld clearance, and labels only affect preference.
 * Labels can never create walkable space. When no trusted room yields a safe point the caller falls
 * back to the older bounded centre/grid search.
 */
internal object WalkableSpawnResolver {

    fun find(
        plan: FloorPlan,
        radius: Float,
        isClear: (Vec2, Float) -> Boolean,
    ): Vec2? {
        val candidates = plan.rooms
            .asSequence()
            .filter { it.confidence >= MIN_ROOM_CONFIDENCE }
            .filterNot(OpenAirRoomPolicy::shouldRemainOpenToSky)
            .mapNotNull { room ->
                val area = polygonArea(room.polygon)
                if (area < MIN_ROOM_AREA_SQ_METERS) null else RoomCandidate(room, area, roomScore(room, area))
            }
            .sortedByDescending { it.score }
            .toList()

        for (candidate in candidates) {
            roomSpawnCandidates(candidate.room).forEach { point ->
                if (isClear(point, radius)) return point
            }
        }
        return null
    }

    private fun roomSpawnCandidates(room: RoomRegion): Sequence<Vec2> = sequence {
        if (room.polygon.size < 3) return@sequence
        val centroid = polygonCentroid(room.polygon)
        if (pointInsidePolygon(centroid, room.polygon)) yield(centroid)

        val minX = room.polygon.minOf { it.x }
        val maxX = room.polygon.maxOf { it.x }
        val minZ = room.polygon.minOf { it.z }
        val maxZ = room.polygon.maxOf { it.z }
        val halfWidth = (maxX - minX) * 0.5f
        val halfDepth = (maxZ - minZ) * 0.5f
        val maxRing = max(
            1,
            max(
                (halfWidth / SAMPLE_GRID_METERS).toInt() + 1,
                (halfDepth / SAMPLE_GRID_METERS).toInt() + 1,
            ),
        ).coerceAtMost(MAX_SAMPLE_RINGS)

        for (ring in 1..maxRing) {
            for (gx in -ring..ring) {
                for (gz in -ring..ring) {
                    if (abs(gx) != ring && abs(gz) != ring) continue
                    val point = Vec2(
                        x = (centroid.x + gx * SAMPLE_GRID_METERS).coerceIn(minX, maxX),
                        z = (centroid.z + gz * SAMPLE_GRID_METERS).coerceIn(minZ, maxZ),
                    )
                    if (pointInsidePolygon(point, room.polygon)) yield(point)
                }
            }
        }
    }

    private fun roomScore(room: RoomRegion, area: Float): Float {
        val areaScore = (sqrt(area) / AREA_NORMALIZER_METERS).coerceIn(0f, 1f)
        val labelScore = labelPriority(room.label)
        return (
            room.confidence.coerceIn(0f, 1f) * 0.48f +
                areaScore * 0.22f +
                labelScore * 0.30f
            ).coerceIn(0f, 1f)
    }

    /**
     * Prefer entrances/foyers and common circulation spaces. Service/private rooms remain usable
     * as a last resort but receive a low score so a tour does not normally begin in a bathroom,
     * storage room, stair shaft or lift lobby when a better room exists.
     */
    private fun labelPriority(label: String?): Float {
        val normalized = label?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return 0.52f
        return when {
            normalized.contains("مدخل") || normalized.contains("entrance") || normalized.contains("foyer") -> 1.0f
            normalized.contains("بهو") || normalized.contains("lobby") -> 0.94f
            normalized.contains("صالة") || normalized.contains("living") || normalized.contains("family") -> 0.88f
            normalized.contains("ممر") || normalized.contains("corridor") || normalized.contains("hallway") -> 0.82f
            normalized.contains("مجلس") || normalized.contains("majlis") -> 0.74f
            normalized.contains("غرفة طعام") || normalized.contains("dining") -> 0.66f
            normalized.contains("غرفة نوم") || normalized.contains("bedroom") -> 0.54f
            normalized.contains("مصلى") || normalized.contains("prayer") -> 0.50f
            normalized.contains("مطبخ") || normalized.contains("kitchen") -> 0.42f
            normalized.contains("مرآب") || normalized.contains("garage") || normalized.contains("parking") -> 0.30f
            normalized.contains("حمام") || normalized.contains("bath") || normalized.contains("toilet") -> 0.10f
            normalized.contains("مخزن") || normalized.contains("storage") || normalized.contains("store") -> 0.12f
            normalized.contains("غسيل") || normalized.contains("laundry") -> 0.14f
            normalized.contains("مغاسل") || normalized.contains("wash basin") -> 0.14f
            normalized.contains("ملابس") || normalized.contains("closet") || normalized.contains("dressing") -> 0.14f
            normalized.contains("خادمة") || normalized.contains("سائق") -> 0.20f
            normalized.contains("مصعد") || normalized.contains("elevator") || normalized.contains("lift") -> 0.05f
            normalized.contains("درج") || normalized.contains("stair") -> 0.08f
            else -> 0.50f
        }
    }

    private fun polygonCentroid(points: List<Vec2>): Vec2 {
        if (points.isEmpty()) return Vec2(0f, 0f)

        var crossSum = 0f
        var xSum = 0f
        var zSum = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = current.x * next.z - next.x * current.z
            crossSum += cross
            xSum += (current.x + next.x) * cross
            zSum += (current.z + next.z) * cross
        }

        if (abs(crossSum) <= EPSILON) {
            return Vec2(
                x = points.sumOf { it.x.toDouble() }.toFloat() / points.size,
                z = points.sumOf { it.z.toDouble() }.toFloat() / points.size,
            )
        }
        val scale = 1f / (3f * crossSum)
        return Vec2(xSum * scale, zSum * scale)
    }

    private fun polygonArea(points: List<Vec2>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.z - next.x * current.z
        }
        return abs(sum) * 0.5f
    }

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (abs(denominator) < EPSILON) EPSILON else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private data class RoomCandidate(
        val room: RoomRegion,
        val area: Float,
        val score: Float,
    )

    private const val MIN_ROOM_CONFIDENCE = 0.70f
    private const val MIN_ROOM_AREA_SQ_METERS = 1.8f
    private const val SAMPLE_GRID_METERS = 0.38f
    private const val MAX_SAMPLE_RINGS = 18
    private const val AREA_NORMALIZER_METERS = 5.5f
    private const val EPSILON = 0.000001f
}
