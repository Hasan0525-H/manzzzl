package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.StairLevelLink
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Level-aware collision and staircase traversal without a heavyweight physics dependency.
 *
 * Every floor keeps its own CollisionWorld and StairTraversalResolver. Crossing a linked staircase
 * changes only the active collision level and global camera elevation; source X/Z geometry is never
 * warped to make two drawings fit. A transition is accepted only at the physical high landing and
 * only when the destination floor is collision-free at the same measured X/Z location.
 */
internal class MultiLevelWalkWorld(private val building: BuildingPlan) {

    private val levels = building.levels.sortedBy { it.levelIndex }
    private val runtimes = levels.associate { level ->
        level.id to LevelRuntime(
            level = level,
            collision = CollisionWorld(level.plan),
            stairs = StairTraversalResolver(level.plan),
        )
    }

    val initialLevelId: String = levels.firstOrNull()?.id ?: ""

    fun findInitialSpawn(radius: Float = CollisionWorld.DEFAULT_PLAYER_RADIUS): BuildingSpawn {
        val runtime = runtimes[initialLevelId]
            ?: return BuildingSpawn(initialLevelId, Vec2(0f, 0f), 0f)
        val position = runtime.collision.findSpawn(radius)
        return BuildingSpawn(
            levelId = runtime.level.id,
            position = position,
            globalElevationMeters = runtime.level.baseElevationMeters,
        )
    }

    fun move(
        levelId: String,
        position: Vec2,
        globalElevationMeters: Float,
        deltaX: Float,
        deltaZ: Float,
        radius: Float = CollisionWorld.DEFAULT_PLAYER_RADIUS,
    ): BuildingMoveResult {
        val runtime = runtimes[levelId]
            ?: return BuildingMoveResult(levelId, position, globalElevationMeters, blocked = true)

        val horizontal = runtime.collision.move(position, deltaX, deltaZ, radius)

        tryDescend(
            runtime = runtime,
            from = position,
            to = horizontal,
            globalElevationMeters = globalElevationMeters,
            radius = radius,
        )?.let { return it }

        val localElevation = (globalElevationMeters - runtime.level.baseElevationMeters)
            .coerceAtLeast(0f)
        val vertical = runtime.stairs.resolveMove(
            from = position,
            to = horizontal,
            currentElevationMeters = localElevation,
        )

        if (!vertical.blockedByVerticalTransition) {
            return BuildingMoveResult(
                levelId = runtime.level.id,
                position = vertical.position,
                globalElevationMeters = runtime.level.baseElevationMeters + vertical.elevationMeters,
                blocked = false,
            )
        }

        tryAscend(
            runtime = runtime,
            from = position,
            to = horizontal,
            globalElevationMeters = globalElevationMeters,
            radius = radius,
        )?.let { return it }

        return BuildingMoveResult(
            levelId = runtime.level.id,
            position = position,
            globalElevationMeters = globalElevationMeters,
            blocked = true,
        )
    }

    private fun tryAscend(
        runtime: LevelRuntime,
        from: Vec2,
        to: Vec2,
        globalElevationMeters: Float,
        radius: Float,
    ): BuildingMoveResult? {
        for (link in building.stairLinks) {
            if (link.lowerLevelId != runtime.level.id || link.confidence < MIN_LINK_CONFIDENCE) continue
            val stair = runtime.level.plan.stairs.getOrNull(link.lowerStairIndex) ?: continue
            val upper = runtimes[link.upperLevelId] ?: continue
            if (!elevationsAgree(runtime.level, stair, upper.level)) continue

            val expectedTop = runtime.level.baseElevationMeters + stair.floorToFloorHeightMeters
            if (abs(globalElevationMeters - expectedTop) > LANDING_ELEVATION_TOLERANCE_METERS) continue
            if (!crossesHighLanding(stair, from, to, ascending = true, radius = radius)) continue
            if (!upper.collision.isClear(to, radius)) continue

            return BuildingMoveResult(
                levelId = upper.level.id,
                position = to,
                globalElevationMeters = upper.level.baseElevationMeters,
                blocked = false,
                levelTransition = LevelTransition.UP,
            )
        }
        return null
    }

    private fun tryDescend(
        runtime: LevelRuntime,
        from: Vec2,
        to: Vec2,
        globalElevationMeters: Float,
        radius: Float,
    ): BuildingMoveResult? {
        if (abs(globalElevationMeters - runtime.level.baseElevationMeters) > LANDING_ELEVATION_TOLERANCE_METERS) {
            return null
        }

        for (link in building.stairLinks) {
            if (link.upperLevelId != runtime.level.id || link.confidence < MIN_LINK_CONFIDENCE) continue
            val lower = runtimes[link.lowerLevelId] ?: continue
            val stair = lower.level.plan.stairs.getOrNull(link.lowerStairIndex) ?: continue
            if (!elevationsAgree(lower.level, stair, runtime.level)) continue
            if (!crossesHighLanding(stair, from, to, ascending = false, radius = radius)) continue

            val lowerHorizontal = lower.collision.move(
                position = from,
                deltaX = to.x - from.x,
                deltaZ = to.z - from.z,
                radius = radius,
            )
            val localTop = stair.floorToFloorHeightMeters
            val vertical = lower.stairs.resolveMove(
                from = from,
                to = lowerHorizontal,
                currentElevationMeters = localTop,
            )
            if (vertical.blockedByVerticalTransition) continue

            return BuildingMoveResult(
                levelId = lower.level.id,
                position = vertical.position,
                globalElevationMeters = lower.level.baseElevationMeters + vertical.elevationMeters,
                blocked = false,
                levelTransition = LevelTransition.DOWN,
            )
        }
        return null
    }

    private fun elevationsAgree(lower: FloorLevel, stair: Staircase, upper: FloorLevel): Boolean {
        val expected = lower.baseElevationMeters + stair.floorToFloorHeightMeters
        return abs(expected - upper.baseElevationMeters) <= MAX_LEVEL_HEIGHT_MISMATCH_METERS
    }

    private fun crossesHighLanding(
        stair: Staircase,
        from: Vec2,
        to: Vec2,
        ascending: Boolean,
        radius: Float,
    ): Boolean {
        val fromLocal = localCoordinates(stair, from)
        val toLocal = localCoordinates(stair, to)
        val halfRun = stair.runMeters * 0.5f
        val halfWidth = stair.widthMeters * 0.5f + radius
        if (abs(fromLocal.across) > halfWidth || abs(toLocal.across) > halfWidth) return false

        val fromInLandingZone = fromLocal.along >= halfRun - LANDING_ZONE_METERS &&
            fromLocal.along <= halfRun + LANDING_ZONE_METERS
        val toInLandingZone = toLocal.along >= halfRun - LANDING_ZONE_METERS &&
            toLocal.along <= halfRun + LANDING_ZONE_METERS
        if (!fromInLandingZone || !toInLandingZone) return false

        return if (ascending) {
            toLocal.along > fromLocal.along + MIN_DIRECTIONAL_PROGRESS_METERS &&
                toLocal.along >= halfRun - TOP_TREAD_ZONE_METERS
        } else {
            toLocal.along < fromLocal.along - MIN_DIRECTIONAL_PROGRESS_METERS &&
                toLocal.along <= halfRun + TOP_TREAD_ZONE_METERS
        }
    }

    private fun localCoordinates(stair: Staircase, point: Vec2): StairLocalPoint {
        val radians = stair.rotationDegrees * (PI.toFloat() / 180f)
        val runX = cos(radians)
        val runZ = sin(radians)
        val widthX = -runZ
        val widthZ = runX
        val dx = point.x - stair.center.x
        val dz = point.z - stair.center.z
        return StairLocalPoint(
            along = dx * runX + dz * runZ,
            across = dx * widthX + dz * widthZ,
        )
    }

    private data class LevelRuntime(
        val level: FloorLevel,
        val collision: CollisionWorld,
        val stairs: StairTraversalResolver,
    )

    private data class StairLocalPoint(
        val along: Float,
        val across: Float,
    )

    companion object {
        private const val MIN_LINK_CONFIDENCE = 0.58f
        private const val MAX_LEVEL_HEIGHT_MISMATCH_METERS = 0.65f
        private const val LANDING_ELEVATION_TOLERANCE_METERS = 0.28f
        private const val LANDING_ZONE_METERS = 0.42f
        private const val TOP_TREAD_ZONE_METERS = 0.24f
        private const val MIN_DIRECTIONAL_PROGRESS_METERS = 0.006f
    }
}

internal data class BuildingSpawn(
    val levelId: String,
    val position: Vec2,
    val globalElevationMeters: Float,
)

internal data class BuildingMoveResult(
    val levelId: String,
    val position: Vec2,
    val globalElevationMeters: Float,
    val blocked: Boolean,
    val levelTransition: LevelTransition? = null,
)

internal enum class LevelTransition {
    UP,
    DOWN,
}
