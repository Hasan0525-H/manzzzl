package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
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
 * Deterministic, offline door interaction runtime.
 *
 * Only doors whose hinge and swing were recovered with sufficient confidence receive a physical
 * moving leaf. The runtime starts those leaves closed, opens them automatically as the player
 * approaches, keeps them open while the player occupies the swing envelope, and closes them again
 * after the player has cleared the doorway. Unknown doors remain framed openings rather than being
 * assigned fabricated hinges.
 *
 * Collision uses the exact animated leaf segment, so visual state and navigation state cannot drift
 * apart. The class is renderer-independent and fully unit-testable on the JVM.
 */
internal class InteractiveDoorWorld(building: BuildingPlan) {

    private val doors = building.levels.flatMap { level ->
        level.plan.doors.mapIndexedNotNull { index, door ->
            if (!door.isInteractive()) return@mapIndexedNotNull null
            DoorRuntime(
                key = DoorKey(level.id, index),
                levelBaseElevationMeters = level.baseElevationMeters,
                door = door,
            )
        }
    }
    private val doorsByLevel = doors.groupBy { it.key.levelId }

    /**
     * Advances all door animations. Returns true when at least one pose changed enough to require a
     * dynamic mesh update.
     */
    fun update(
        currentLevelId: String,
        playerPosition: Vec2,
        deltaSeconds: Float,
        playerRadius: Float = CollisionWorld.DEFAULT_PLAYER_RADIUS,
    ): Boolean {
        if (deltaSeconds <= 0f || doors.isEmpty()) return false
        var changed = false

        for (runtime in doors) {
            val onCurrentLevel = runtime.key.levelId == currentLevelId
            val distanceToOpening = distance(playerPosition, runtime.door.center)
            val playerNearSwingEnvelope = onCurrentLevel &&
                distanceToLeaf(playerPosition, runtime.pose()) <= playerRadius + CLOSE_SAFETY_MARGIN_METERS

            val shouldOpen = onCurrentLevel && (
                distanceToOpening <= OPEN_TRIGGER_DISTANCE_METERS || playerNearSwingEnvelope
            )
            val shouldClose = !onCurrentLevel || (
                distanceToOpening >= CLOSE_TRIGGER_DISTANCE_METERS && !playerNearSwingEnvelope
            )

            runtime.targetAngleDegrees = when {
                shouldOpen -> OPEN_ANGLE_DEGREES
                shouldClose -> 0f
                else -> runtime.targetAngleDegrees
            }

            val before = runtime.angleDegrees
            runtime.angleDegrees = moveToward(
                current = runtime.angleDegrees,
                target = runtime.targetAngleDegrees,
                maxDelta = DOOR_ANGULAR_SPEED_DEGREES_PER_SECOND * deltaSeconds,
            )
            if (abs(runtime.angleDegrees - before) >= MESH_DIRTY_ANGLE_EPSILON_DEGREES) {
                changed = true
            }
        }
        return changed
    }

    /**
     * Resolves player movement against the currently animated physical door leaves. Wall/opening
     * collision is handled separately by MultiLevelWalkWorld; this layer only adds moving leaves.
     */
    fun resolveMove(
        levelId: String,
        from: Vec2,
        to: Vec2,
        radius: Float = CollisionWorld.DEFAULT_PLAYER_RADIUS,
    ): Vec2 {
        val levelDoors = doorsByLevel[levelId].orEmpty()
        if (levelDoors.isEmpty()) return to

        val dx = to.x - from.x
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dz * dz)
        val steps = ceil(distance / MAX_COLLISION_SUBSTEP_METERS)
            .toInt()
            .coerceIn(1, MAX_COLLISION_SUBSTEPS)
        val stepX = dx / steps
        val stepZ = dz / steps

        var result = from
        repeat(steps) {
            var candidate = Vec2(result.x + stepX, result.z + stepZ)
            var pass = 0
            while (pass < COLLISION_RESOLUTION_PASSES) {
                var changed = false
                for (runtime in levelDoors) {
                    val resolved = resolveLeafPenetration(candidate, runtime.pose(), radius)
                    if (distanceSquared(candidate, resolved) > POSITION_EPSILON_SQ) {
                        candidate = resolved
                        changed = true
                    }
                }
                if (!changed) break
                pass++
            }
            result = candidate
        }
        return result
    }

    /** Returns immutable poses for rendering all known physical leaves. */
    fun poses(): List<DoorLeafPose> = doors.map { it.pose() }

    fun interactiveDoorCount(): Int = doors.size

    internal fun angleDegrees(levelId: String, doorIndex: Int): Float? =
        doorsByLevel[levelId]
            ?.firstOrNull { it.key.doorIndex == doorIndex }
            ?.angleDegrees

    private fun resolveLeafPenetration(candidate: Vec2, pose: DoorLeafPose, radius: Float): Vec2 {
        val start = pose.hinge
        val end = pose.tip
        val vx = end.x - start.x
        val vz = end.z - start.z
        val lengthSquared = vx * vx + vz * vz
        if (lengthSquared <= EPSILON) return candidate

        val projection = (((candidate.x - start.x) * vx + (candidate.z - start.z) * vz) / lengthSquared)
            .coerceIn(0f, 1f)
        val closestX = start.x + vx * projection
        val closestZ = start.z + vz * projection
        val dx = candidate.x - closestX
        val dz = candidate.z - closestZ
        val minimum = radius + DOOR_LEAF_THICKNESS_METERS * 0.5f + CONTACT_SKIN_METERS
        val distanceSquared = dx * dx + dz * dz
        if (distanceSquared >= minimum * minimum) return candidate

        val distance = sqrt(distanceSquared)
        val nx: Float
        val nz: Float
        if (distance > EPSILON) {
            nx = dx / distance
            nz = dz / distance
        } else {
            val length = sqrt(lengthSquared)
            nx = -vz / length
            nz = vx / length
        }
        val push = minimum - distance
        return Vec2(candidate.x + nx * push, candidate.z + nz * push)
    }

    private fun distanceToLeaf(point: Vec2, pose: DoorLeafPose): Float {
        val start = pose.hinge
        val end = pose.tip
        val vx = end.x - start.x
        val vz = end.z - start.z
        val lengthSquared = vx * vx + vz * vz
        if (lengthSquared <= EPSILON) return distance(point, start)
        val projection = (((point.x - start.x) * vx + (point.z - start.z) * vz) / lengthSquared)
            .coerceIn(0f, 1f)
        val closest = Vec2(start.x + vx * projection, start.z + vz * projection)
        return distance(point, closest)
    }

    private data class DoorRuntime(
        val key: DoorKey,
        val levelBaseElevationMeters: Float,
        val door: DoorOpening,
        var angleDegrees: Float = 0f,
        var targetAngleDegrees: Float = 0f,
    ) {
        fun pose(): DoorLeafPose {
            val axisRadians = door.rotationDegrees * (PI.toFloat() / 180f)
            val axisX = cos(axisRadians)
            val axisZ = sin(axisRadians)
            val normalX = -axisZ
            val normalZ = axisX
            val hingeSign = if (door.hingeSide == DoorHingeSide.AXIS_START) -1f else 1f
            val closedSign = -hingeSign
            val swingSign = if (door.swingSide == DoorSwingSide.POSITIVE_NORMAL) 1f else -1f
            val hinge = Vec2(
                x = door.center.x + axisX * door.widthMeters * 0.5f * hingeSign,
                z = door.center.z + axisZ * door.widthMeters * 0.5f * hingeSign,
            )

            val radians = angleDegrees * (PI.toFloat() / 180f)
            val closedX = axisX * closedSign
            val closedZ = axisZ * closedSign
            val swingX = normalX * swingSign
            val swingZ = normalZ * swingSign
            val directionX = closedX * cos(radians) + swingX * sin(radians)
            val directionZ = closedZ * cos(radians) + swingZ * sin(radians)
            val leafLength = (door.widthMeters - DOOR_LEAF_JAMB_GAP_METERS)
                .coerceAtLeast(MIN_DOOR_LEAF_LENGTH_METERS)

            return DoorLeafPose(
                key = key,
                hinge = hinge,
                tip = Vec2(
                    x = hinge.x + directionX * leafLength,
                    z = hinge.z + directionZ * leafLength,
                ),
                direction = Vec2(directionX, directionZ),
                leafLengthMeters = leafLength,
                baseElevationMeters = levelBaseElevationMeters,
                heightMeters = DEFAULT_DOOR_LEAF_HEIGHT_METERS,
                angleDegrees = angleDegrees,
                openFraction = (angleDegrees / OPEN_ANGLE_DEGREES).coerceIn(0f, 1f),
            )
        }
    }

    companion object {
        private const val MIN_SWING_CONFIDENCE = 0.64f
        private const val OPEN_TRIGGER_DISTANCE_METERS = 1.55f
        private const val CLOSE_TRIGGER_DISTANCE_METERS = 2.15f
        private const val CLOSE_SAFETY_MARGIN_METERS = 0.22f
        private const val OPEN_ANGLE_DEGREES = 88f
        private const val DOOR_ANGULAR_SPEED_DEGREES_PER_SECOND = 150f
        private const val MESH_DIRTY_ANGLE_EPSILON_DEGREES = 0.02f
        private const val DOOR_LEAF_THICKNESS_METERS = 0.042f
        private const val DOOR_LEAF_JAMB_GAP_METERS = 0.035f
        private const val MIN_DOOR_LEAF_LENGTH_METERS = 0.55f
        private const val DEFAULT_DOOR_LEAF_HEIGHT_METERS = 2.15f
        private const val CONTACT_SKIN_METERS = 0.012f
        private const val MAX_COLLISION_SUBSTEP_METERS = 0.05f
        private const val MAX_COLLISION_SUBSTEPS = 16
        private const val COLLISION_RESOLUTION_PASSES = 3
        private const val POSITION_EPSILON_SQ = 0.00000001f
        private const val EPSILON = 0.000001f

        private fun DoorOpening.isInteractive(): Boolean =
            hingeSide != DoorHingeSide.UNKNOWN &&
                swingSide != DoorSwingSide.UNKNOWN &&
                swingConfidence >= MIN_SWING_CONFIDENCE

        private fun moveToward(current: Float, target: Float, maxDelta: Float): Float = when {
            current < target -> min(current + maxDelta, target)
            current > target -> max(current - maxDelta, target)
            else -> current
        }

        private fun distance(a: Vec2, b: Vec2): Float =
            sqrt(distanceSquared(a, b))

        private fun distanceSquared(a: Vec2, b: Vec2): Float {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return dx * dx + dz * dz
        }
    }
}

internal data class DoorKey(
    val levelId: String,
    val doorIndex: Int,
)

internal data class DoorLeafPose(
    val key: DoorKey,
    val hinge: Vec2,
    val tip: Vec2,
    val direction: Vec2,
    val leafLengthMeters: Float,
    val baseElevationMeters: Float,
    val heightMeters: Float,
    val angleDegrees: Float,
    val openFraction: Float,
)
