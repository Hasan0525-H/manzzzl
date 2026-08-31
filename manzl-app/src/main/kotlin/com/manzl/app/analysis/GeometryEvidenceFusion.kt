package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Canonical geometry guard between semantic observations and the generated house.
 *
 * A local model/provider is allowed to suggest semantics, never to silently rewrite measured
 * topology. Doors/windows must sit close to a detected wall; rooms must be bounded and have
 * meaningful area; stairs must fit plausible residential dimensions. Explicit user corrections
 * receive a lower confidence threshold but still pass basic geometry safety checks.
 */
internal object GeometryEvidenceFusion {

    fun fuse(base: FloorPlan, evidence: List<SemanticEvidence>): FloorPlan {
        if (evidence.isEmpty()) return base

        val doors = ArrayList(base.doors)
        val windows = ArrayList(base.windows)
        val stairs = ArrayList(base.stairs)
        val rooms = ArrayList(base.rooms)

        for (item in evidence.sortedByDescending { sourcePriority(it.source) * it.confidence }) {
            if (item.confidence < minimumConfidence(item.source)) continue
            when (item.kind) {
                SemanticKind.DOOR -> acceptDoor(base, item)?.let { mergeDoor(doors, it) }
                SemanticKind.WINDOW -> acceptWindow(base, item)?.let { mergeWindow(windows, it) }
                SemanticKind.STAIR -> acceptStair(base, item)?.let { mergeStair(stairs, it) }
                SemanticKind.ROOM -> acceptRoom(base, item)?.let { mergeRoom(rooms, it) }
            }
        }

        return base.copy(
            doors = doors,
            windows = windows,
            stairs = stairs,
            rooms = rooms,
        )
    }

    private fun acceptDoor(plan: FloorPlan, item: SemanticEvidence): DoorOpening? {
        val width = item.widthMeters ?: return null
        if (width !in MIN_DOOR_WIDTH..MAX_DOOR_WIDTH) return null
        val wall = nearestWall(plan.walls, item.center) ?: return null
        if (distanceToWall(wall, item.center) > OPENING_WALL_DISTANCE) return null
        val rotation = item.rotationDegrees ?: wallRotation(wall)
        return DoorOpening(
            center = projectOntoWall(wall, item.center),
            widthMeters = width,
            rotationDegrees = normalizeAxisRotation(rotation),
            confidence = item.confidence,
        )
    }

    private fun acceptWindow(plan: FloorPlan, item: SemanticEvidence): WindowOpening? {
        val width = item.widthMeters ?: return null
        if (width !in MIN_WINDOW_WIDTH..MAX_WINDOW_WIDTH) return null
        val wall = nearestWall(plan.walls, item.center) ?: return null
        if (distanceToWall(wall, item.center) > OPENING_WALL_DISTANCE) return null
        val rotation = item.rotationDegrees ?: wallRotation(wall)
        return WindowOpening(
            center = projectOntoWall(wall, item.center),
            widthMeters = width,
            rotationDegrees = normalizeAxisRotation(rotation),
            confidence = item.confidence,
        )
    }

    private fun acceptStair(plan: FloorPlan, item: SemanticEvidence): Staircase? {
        val width = item.widthMeters ?: return null
        val run = item.lengthMeters ?: return null
        if (width !in MIN_STAIR_WIDTH..MAX_STAIR_WIDTH) return null
        if (run !in MIN_STAIR_RUN..MAX_STAIR_RUN) return null
        if (!insideBounds(plan, item.center, margin = 0.10f)) return null
        val floorHeight = DEFAULT_FLOOR_TO_FLOOR
        val steps = (floorHeight / TARGET_RISER_HEIGHT).roundToInt().coerceIn(14, 22)
        return Staircase(
            center = item.center,
            widthMeters = width,
            runMeters = run,
            rotationDegrees = normalizeRotation(item.rotationDegrees ?: 0f),
            stepCount = steps,
            floorToFloorHeightMeters = floorHeight,
            confidence = item.confidence,
        )
    }

    private fun acceptRoom(plan: FloorPlan, item: SemanticEvidence): RoomRegion? {
        val polygon = item.polygon
        if (polygon.size < 3) return null
        if (polygon.any { !insideBounds(plan, it, margin = 0f) }) return null
        if (polygonArea(polygon) < MIN_ROOM_AREA) return null
        return RoomRegion(
            id = "${item.source.name.lowercase()}-${stableRoomKey(polygon)}",
            polygon = polygon,
            label = item.label,
            confidence = item.confidence,
        )
    }

    private fun mergeDoor(target: MutableList<DoorOpening>, candidate: DoorOpening) {
        val index = target.indexOfFirst { squaredDistance(it.center, candidate.center) < DUPLICATE_OPENING_DISTANCE_SQ }
        if (index < 0) target += candidate else if (candidate.confidence > target[index].confidence) target[index] = candidate
    }

    private fun mergeWindow(target: MutableList<WindowOpening>, candidate: WindowOpening) {
        val index = target.indexOfFirst { squaredDistance(it.center, candidate.center) < DUPLICATE_OPENING_DISTANCE_SQ }
        if (index < 0) target += candidate else if (candidate.confidence > target[index].confidence) target[index] = candidate
    }

    private fun mergeStair(target: MutableList<Staircase>, candidate: Staircase) {
        val index = target.indexOfFirst { squaredDistance(it.center, candidate.center) < DUPLICATE_STAIR_DISTANCE_SQ }
        if (index < 0) target += candidate else if (candidate.confidence > target[index].confidence) target[index] = candidate
    }

    private fun mergeRoom(target: MutableList<RoomRegion>, candidate: RoomRegion) {
        val candidateCenter = polygonCentroid(candidate.polygon)
        val index = target.indexOfFirst {
            squaredDistance(polygonCentroid(it.polygon), candidateCenter) < DUPLICATE_ROOM_CENTER_DISTANCE_SQ
        }
        if (index < 0) {
            target += candidate
            return
        }

        val existing = target[index]
        target[index] = when {
            existing.label.isNullOrBlank() && !candidate.label.isNullOrBlank() -> existing.copy(
                label = candidate.label,
                confidence = max(existing.confidence, candidate.confidence),
            )
            candidate.confidence > existing.confidence -> candidate.copy(
                label = candidate.label ?: existing.label,
            )
            else -> existing
        }
    }

    private fun nearestWall(walls: List<WallSegment>, point: Vec2): WallSegment? =
        walls.minByOrNull { distanceToWall(it, point) }

    private fun distanceToWall(wall: WallSegment, point: Vec2): Float {
        val projected = projectOntoWall(wall, point)
        return sqrt(squaredDistance(projected, point))
    }

    private fun projectOntoWall(wall: WallSegment, point: Vec2): Vec2 {
        val vx = wall.end.x - wall.start.x
        val vz = wall.end.z - wall.start.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= 0.000001f) return wall.start
        val t = (((point.x - wall.start.x) * vx + (point.z - wall.start.z) * vz) / lengthSq)
            .coerceIn(0f, 1f)
        return Vec2(
            wall.start.x + vx * t,
            wall.start.z + vz * t,
        )
    }

    private fun wallRotation(wall: WallSegment): Float {
        val dx = abs(wall.end.x - wall.start.x)
        val dz = abs(wall.end.z - wall.start.z)
        return if (dx >= dz) 0f else 90f
    }

    private fun normalizeAxisRotation(value: Float): Float {
        val normalized = normalizeRotation(value)
        return if (normalized in 45f..135f || normalized in 225f..315f) 90f else 0f
    }

    private fun normalizeRotation(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    private fun insideBounds(plan: FloorPlan, point: Vec2, margin: Float): Boolean {
        val halfWidth = plan.widthMeters / 2f - margin
        val halfDepth = plan.depthMeters / 2f - margin
        return point.x in -halfWidth..halfWidth && point.z in -halfDepth..halfDepth
    }

    private fun polygonArea(points: List<Vec2>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return abs(sum) * 0.5f
    }

    private fun polygonCentroid(points: List<Vec2>): Vec2 {
        if (points.isEmpty()) return Vec2(0f, 0f)
        var x = 0f
        var z = 0f
        for (point in points) {
            x += point.x
            z += point.z
        }
        return Vec2(x / points.size, z / points.size)
    }

    private fun stableRoomKey(points: List<Vec2>): String {
        val center = polygonCentroid(points)
        return "${(center.x * 100).roundToInt()}-${(center.z * 100).roundToInt()}-${points.size}"
    }

    private fun squaredDistance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private fun sourcePriority(source: EvidenceSource): Float = when (source) {
        EvidenceSource.USER_CORRECTION -> 1.25f
        EvidenceSource.LOCAL_AI -> 1.0f
        EvidenceSource.CLASSICAL_CV -> 0.92f
    }

    private fun minimumConfidence(source: EvidenceSource): Float = when (source) {
        EvidenceSource.USER_CORRECTION -> 0.20f
        EvidenceSource.LOCAL_AI -> 0.58f
        EvidenceSource.CLASSICAL_CV -> 0.64f
    }

    private const val MIN_DOOR_WIDTH = 0.62f
    private const val MAX_DOOR_WIDTH = 1.65f
    private const val MIN_WINDOW_WIDTH = 0.40f
    private const val MAX_WINDOW_WIDTH = 4.20f
    private const val MIN_STAIR_WIDTH = 0.72f
    private const val MAX_STAIR_WIDTH = 2.60f
    private const val MIN_STAIR_RUN = 1.60f
    private const val MAX_STAIR_RUN = 8.50f
    private const val DEFAULT_FLOOR_TO_FLOOR = 3.20f
    private const val TARGET_RISER_HEIGHT = 0.175f
    private const val MIN_ROOM_AREA = 2.0f
    private const val OPENING_WALL_DISTANCE = 0.36f
    private const val DUPLICATE_OPENING_DISTANCE_SQ = 0.18f * 0.18f
    private const val DUPLICATE_STAIR_DISTANCE_SQ = 0.60f * 0.60f
    private const val DUPLICATE_ROOM_CENTER_DISTANCE_SQ = 0.75f * 0.75f
}
