package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight 2D collision world for the first-person camera.
 *
 * The player is represented by a circle on the X/Z floor plane. Movement is sub-stepped so a
 * low-frame-rate device cannot tunnel through thin walls or compact structural columns, then any
 * penetration is projected out of nearby capsules/oriented column boxes. Window metadata adds a
 * solid barrier across raster wall gaps, while only verified door spans on the same measured wall
 * axis may become traversable. This prevents a door beside a corner/crossing wall from accidentally
 * punching a passage through the wrong wall.
 */
internal class CollisionWorld(private val plan: FloorPlan) {

    private val barriers: List<CollisionBarrier> = buildList {
        plan.walls.forEach { wall -> add(CollisionBarrier(wall, permitsDoorPassage = true)) }
        plan.windows.forEach { window ->
            window.toCollisionWall()?.let { wall ->
                add(CollisionBarrier(wall, permitsDoorPassage = false))
            }
        }
    }

    private val columns: List<StructuralColumn> = plan.columns.filter { column ->
        column.confidence >= MIN_COLUMN_COLLISION_CONFIDENCE &&
            column.widthMeters >= MIN_COLUMN_COLLISION_DIMENSION_METERS &&
            column.depthMeters >= MIN_COLUMN_COLLISION_DIMENSION_METERS
    }

    fun move(position: Vec2, deltaX: Float, deltaZ: Float, radius: Float): Vec2 {
        val distance = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val steps = ceil(distance / MAX_SUBSTEP_METERS)
            .toInt()
            .coerceIn(1, MAX_SUBSTEPS)
        val stepX = deltaX / steps
        val stepZ = deltaZ / steps

        var result = position
        repeat(steps) {
            result = resolvePenetration(
                candidate = Vec2(result.x + stepX, result.z + stepZ),
                radius = radius,
            )
        }
        return result
    }

    fun findSpawn(radius: Float = DEFAULT_PLAYER_RADIUS): Vec2 {
        WalkableSpawnResolver.find(plan, radius, ::isClear)?.let { return it }

        val centre = Vec2(0f, 0f)
        if (isClear(centre, radius)) return centre

        val halfWidth = (plan.widthMeters / 2f - radius - BOUNDARY_PADDING).coerceAtLeast(0.1f)
        val halfDepth = (plan.depthMeters / 2f - radius - BOUNDARY_PADDING).coerceAtLeast(0.1f)
        val maxRing = max(
            ceil(halfWidth / SPAWN_GRID_METERS).toInt(),
            ceil(halfDepth / SPAWN_GRID_METERS).toInt(),
        )

        for (ring in 1..maxRing) {
            for (gx in -ring..ring) {
                for (gz in -ring..ring) {
                    if (abs(gx) != ring && abs(gz) != ring) continue
                    val candidate = Vec2(
                        x = (gx * SPAWN_GRID_METERS).coerceIn(-halfWidth, halfWidth),
                        z = (gz * SPAWN_GRID_METERS).coerceIn(-halfDepth, halfDepth),
                    )
                    if (isClear(candidate, radius)) return candidate
                }
            }
        }

        return resolvePenetration(centre, radius)
    }

    fun isClear(point: Vec2, radius: Float = DEFAULT_PLAYER_RADIUS): Boolean {
        val halfWidth = plan.widthMeters / 2f - BOUNDARY_PADDING
        val halfDepth = plan.depthMeters / 2f - BOUNDARY_PADDING
        if (point.x - radius < -halfWidth || point.x + radius > halfWidth) return false
        if (point.z - radius < -halfDepth || point.z + radius > halfDepth) return false

        if (barriers.any { barrier -> collidesWithBarrier(point, radius, barrier) }) return false
        return columns.none { column -> collidesWithColumn(point, radius, column) }
    }

    private fun resolvePenetration(candidate: Vec2, radius: Float): Vec2 {
        val halfWidth = (plan.widthMeters / 2f - radius - BOUNDARY_PADDING).coerceAtLeast(0f)
        val halfDepth = (plan.depthMeters / 2f - radius - BOUNDARY_PADDING).coerceAtLeast(0f)
        var x = candidate.x.coerceIn(-halfWidth, halfWidth)
        var z = candidate.z.coerceIn(-halfDepth, halfDepth)

        var pass = 0
        while (pass < RESOLUTION_PASSES) {
            var changed = false
            for (barrier in barriers) {
                val wall = barrier.wall
                val start = wall.start
                val end = wall.end
                val vx = end.x - start.x
                val vz = end.z - start.z
                val lengthSquared = vx * vx + vz * vz
                if (lengthSquared <= EPSILON) continue

                val projection = (((x - start.x) * vx + (z - start.z) * vz) / lengthSquared)
                    .coerceIn(0f, 1f)
                val closestX = start.x + vx * projection
                val closestZ = start.z + vz * projection

                if (barrier.permitsDoorPassage && isDoorPassage(wall, projection, radius)) continue

                val dx = x - closestX
                val dz = z - closestZ
                val minDistance = radius + wall.thicknessMeters / 2f + CONTACT_SKIN_METERS
                val distanceSquared = dx * dx + dz * dz
                if (distanceSquared >= minDistance * minDistance) continue

                val distance = sqrt(distanceSquared)
                val nx: Float
                val nz: Float
                if (distance > EPSILON) {
                    nx = dx / distance
                    nz = dz / distance
                } else {
                    val wallLength = sqrt(lengthSquared)
                    nx = -vz / wallLength
                    nz = vx / wallLength
                }

                val push = minDistance - distance
                x += nx * push
                z += nz * push
                x = x.coerceIn(-halfWidth, halfWidth)
                z = z.coerceIn(-halfDepth, halfDepth)
                changed = true
            }

            for (column in columns) {
                val correction = columnPenetrationCorrection(Vec2(x, z), radius, column) ?: continue
                x = (x + correction.x).coerceIn(-halfWidth, halfWidth)
                z = (z + correction.z).coerceIn(-halfDepth, halfDepth)
                changed = true
            }

            if (!changed) break
            pass++
        }

        return Vec2(x, z)
    }

    private fun collidesWithBarrier(point: Vec2, radius: Float, barrier: CollisionBarrier): Boolean {
        val wall = barrier.wall
        val vx = wall.end.x - wall.start.x
        val vz = wall.end.z - wall.start.z
        val lengthSquared = vx * vx + vz * vz
        if (lengthSquared <= EPSILON) return false

        val projection = (((point.x - wall.start.x) * vx + (point.z - wall.start.z) * vz) / lengthSquared)
            .coerceIn(0f, 1f)
        if (barrier.permitsDoorPassage && isDoorPassage(wall, projection, radius)) return false

        val closestX = wall.start.x + vx * projection
        val closestZ = wall.start.z + vz * projection
        val dx = point.x - closestX
        val dz = point.z - closestZ
        val minDistance = radius + wall.thicknessMeters / 2f + CONTACT_SKIN_METERS
        return dx * dx + dz * dz < minDistance * minDistance
    }

    /** Conservative circle-vs-oriented-box test used for verified structural columns. */
    private fun collidesWithColumn(point: Vec2, radius: Float, column: StructuralColumn): Boolean {
        val frame = columnFrame(column)
        val dx = point.x - column.center.x
        val dz = point.z - column.center.z
        val along = dx * frame.ux + dz * frame.uz
        val depth = dx * frame.nx + dz * frame.nz
        val halfAlong = column.widthMeters * 0.5f + radius + CONTACT_SKIN_METERS
        val halfDepth = column.depthMeters * 0.5f + radius + CONTACT_SKIN_METERS
        return abs(along) < halfAlong && abs(depth) < halfDepth
    }

    private fun columnPenetrationCorrection(
        point: Vec2,
        radius: Float,
        column: StructuralColumn,
    ): Vec2? {
        val frame = columnFrame(column)
        val dx = point.x - column.center.x
        val dz = point.z - column.center.z
        val along = dx * frame.ux + dz * frame.uz
        val depth = dx * frame.nx + dz * frame.nz
        val halfAlong = column.widthMeters * 0.5f + radius + CONTACT_SKIN_METERS
        val halfDepth = column.depthMeters * 0.5f + radius + CONTACT_SKIN_METERS
        val alongPenetration = halfAlong - abs(along)
        val depthPenetration = halfDepth - abs(depth)
        if (alongPenetration <= 0f || depthPenetration <= 0f) return null

        return if (alongPenetration <= depthPenetration) {
            val sign = if (along < 0f) -1f else 1f
            Vec2(
                x = frame.ux * (alongPenetration + COLUMN_RESOLUTION_SKIN_METERS) * sign,
                z = frame.uz * (alongPenetration + COLUMN_RESOLUTION_SKIN_METERS) * sign,
            )
        } else {
            val sign = if (depth < 0f) -1f else 1f
            Vec2(
                x = frame.nx * (depthPenetration + COLUMN_RESOLUTION_SKIN_METERS) * sign,
                z = frame.nz * (depthPenetration + COLUMN_RESOLUTION_SKIN_METERS) * sign,
            )
        }
    }

    private fun columnFrame(column: StructuralColumn): ColumnFrame {
        val radians = column.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        return ColumnFrame(ux = ux, uz = uz, nx = -uz, nz = ux)
    }

    private fun isDoorPassage(wall: WallSegment, wallProjection: Float, radius: Float): Boolean {
        if (plan.doors.isEmpty()) return false

        val vx = wall.end.x - wall.start.x
        val vz = wall.end.z - wall.start.z
        val wallLength = sqrt(vx * vx + vz * vz)
        if (wallLength <= EPSILON) return false

        return plan.doors.any { door ->
            door.widthMeters > radius * 2f + MIN_DOOR_CLEARANCE_METERS &&
                doorBelongsToWall(door, wall, vx, vz, wallLength, wallProjection, radius)
        }
    }

    private fun doorBelongsToWall(
        door: DoorOpening,
        wall: WallSegment,
        vx: Float,
        vz: Float,
        wallLength: Float,
        wallProjection: Float,
        radius: Float,
    ): Boolean {
        val wallRotation = Math.toDegrees(atan2(vz.toDouble(), vx.toDouble())).toFloat()
        if (axisAngleDifference(wallRotation, door.rotationDegrees) > MAX_DOOR_AXIS_ERROR_DEGREES) {
            return false
        }

        val lengthSquared = wallLength * wallLength
        val doorProjection = (((door.center.x - wall.start.x) * vx +
            (door.center.z - wall.start.z) * vz) / lengthSquared).coerceIn(0f, 1f)
        val wallX = wall.start.x + vx * doorProjection
        val wallZ = wall.start.z + vz * doorProjection
        val perpendicularDistance = sqrt(
            (door.center.x - wallX) * (door.center.x - wallX) +
                (door.center.z - wallZ) * (door.center.z - wallZ)
        )
        if (perpendicularDistance > wall.thicknessMeters + DOOR_ASSOCIATION_TOLERANCE_METERS) return false

        val alongDistance = abs(wallProjection - doorProjection) * wallLength
        val usableHalfWidth = door.widthMeters / 2f - radius * DOOR_EDGE_SAFETY_FACTOR
        return usableHalfWidth > 0f && alongDistance <= usableHalfWidth
    }

    private fun axisAngleDifference(a: Float, b: Float): Float {
        val na = normalizeHalfTurn(a)
        val nb = normalizeHalfTurn(b)
        val delta = abs(na - nb)
        return min(delta, 180f - delta)
    }

    private fun normalizeHalfTurn(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private fun WindowOpening.toCollisionWall(): WallSegment? {
        if (widthMeters <= MIN_WINDOW_COLLISION_WIDTH_METERS) return null
        val radians = rotationDegrees * (PI.toFloat() / 180f)
        val axisX = cos(radians)
        val axisZ = sin(radians)
        val half = widthMeters * 0.5f
        return WallSegment(
            start = Vec2(center.x - axisX * half, center.z - axisZ * half),
            end = Vec2(center.x + axisX * half, center.z + axisZ * half),
            thicknessMeters = WINDOW_COLLISION_THICKNESS_METERS,
            heightMeters = 1f,
            confidence = confidence,
        )
    }

    private data class CollisionBarrier(
        val wall: WallSegment,
        val permitsDoorPassage: Boolean,
    )

    private data class ColumnFrame(
        val ux: Float,
        val uz: Float,
        val nx: Float,
        val nz: Float,
    )

    companion object {
        const val DEFAULT_PLAYER_RADIUS = 0.27f
        private const val MAX_SUBSTEP_METERS = 0.055f
        private const val MAX_SUBSTEPS = 12
        private const val RESOLUTION_PASSES = 5
        private const val SPAWN_GRID_METERS = 0.45f
        private const val BOUNDARY_PADDING = 0.04f
        private const val CONTACT_SKIN_METERS = 0.012f
        private const val COLUMN_RESOLUTION_SKIN_METERS = 0.002f
        private const val MIN_DOOR_CLEARANCE_METERS = 0.08f
        private const val DOOR_ASSOCIATION_TOLERANCE_METERS = 0.18f
        private const val MAX_DOOR_AXIS_ERROR_DEGREES = 14f
        private const val DOOR_EDGE_SAFETY_FACTOR = 0.62f
        private const val WINDOW_COLLISION_THICKNESS_METERS = 0.08f
        private const val MIN_WINDOW_COLLISION_WIDTH_METERS = 0.20f
        private const val MIN_COLUMN_COLLISION_CONFIDENCE = 0.74f
        private const val MIN_COLUMN_COLLISION_DIMENSION_METERS = 0.10f
        private const val EPSILON = 0.000001f
    }
}
