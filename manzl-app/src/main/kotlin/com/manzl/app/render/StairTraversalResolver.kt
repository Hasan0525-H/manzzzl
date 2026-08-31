package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Resolves the walker's vertical support against accepted staircase geometry.
 *
 * The renderer is intentionally not a full rigid-body engine. This class adds the one vertical
 * constraint we need for architectural walkthroughs: the camera can climb and descend adjacent
 * treads, but cannot teleport onto the middle of a staircase from the side or walk off the top
 * into empty space before an upper floor has been linked.
 */
internal class StairTraversalResolver(plan: FloorPlan) {

    private val stairs = plan.stairs.filter { staircase ->
        staircase.confidence >= MIN_TRAVERSAL_CONFIDENCE &&
            staircase.widthMeters >= MIN_STAIR_WIDTH_METERS &&
            staircase.runMeters >= MIN_STAIR_RUN_METERS &&
            staircase.stepCount in MIN_STEPS..MAX_STEPS &&
            staircase.floorToFloorHeightMeters > 0f &&
            staircase.floorToFloorHeightMeters / staircase.stepCount <= MAX_WALKABLE_RISER_METERS
    }

    fun resolveMove(
        from: Vec2,
        to: Vec2,
        currentElevationMeters: Float,
    ): VerticalMoveResult {
        val targetElevation = elevationAt(to)
        val delta = targetElevation - currentElevationMeters

        if (abs(delta) <= MAX_VERTICAL_TRANSITION_METERS) {
            return VerticalMoveResult(
                position = to,
                elevationMeters = targetElevation,
                blockedByVerticalTransition = false,
            )
        }

        // A large change means the camera tried to enter a staircase from its side/high tread, or
        // leave the top into a level that does not exist yet. Keep the previous stable support.
        return VerticalMoveResult(
            position = from,
            elevationMeters = currentElevationMeters,
            blockedByVerticalTransition = true,
        )
    }

    fun elevationAt(position: Vec2): Float {
        var elevation = 0f
        for (staircase in stairs) {
            val support = supportHeight(staircase, position) ?: continue
            elevation = max(elevation, support)
        }
        return elevation
    }

    private fun supportHeight(staircase: Staircase, position: Vec2): Float? {
        val radians = staircase.rotationDegrees * (PI.toFloat() / 180f)
        val runX = cos(radians)
        val runZ = sin(radians)
        val widthX = -runZ
        val widthZ = runX

        val dx = position.x - staircase.center.x
        val dz = position.z - staircase.center.z
        val along = dx * runX + dz * runZ
        val across = dx * widthX + dz * widthZ

        val halfRun = staircase.runMeters * 0.5f
        val halfWidth = staircase.widthMeters * 0.5f
        if (along < -halfRun - EDGE_EPSILON_METERS || along > halfRun + EDGE_EPSILON_METERS) return null
        if (abs(across) > halfWidth + EDGE_EPSILON_METERS) return null

        val steps = staircase.stepCount
        val treadDepth = staircase.runMeters / steps.toFloat()
        val riserHeight = staircase.floorToFloorHeightMeters / steps.toFloat()
        val distanceFromLowEnd = (along + halfRun).coerceIn(0f, staircase.runMeters)
        val treadIndex = (distanceFromLowEnd / treadDepth)
            .toInt()
            .coerceIn(0, steps - 1)
        return riserHeight * (treadIndex + 1)
    }

    companion object {
        private const val MIN_TRAVERSAL_CONFIDENCE = 0.66f
        private const val MIN_STAIR_WIDTH_METERS = 0.70f
        private const val MIN_STAIR_RUN_METERS = 1.45f
        private const val MIN_STEPS = 12
        private const val MAX_STEPS = 32
        private const val MAX_WALKABLE_RISER_METERS = 0.235f
        private const val MAX_VERTICAL_TRANSITION_METERS = 0.255f
        private const val EDGE_EPSILON_METERS = 0.012f
    }
}

internal data class VerticalMoveResult(
    val position: Vec2,
    val elevationMeters: Float,
    val blockedByVerticalTransition: Boolean,
)
