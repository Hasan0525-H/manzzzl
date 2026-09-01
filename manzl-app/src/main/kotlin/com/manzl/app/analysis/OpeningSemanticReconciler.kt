package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.WindowOpening
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Resolves the one topology conflict that geometry-only gap detection cannot decide reliably:
 * a narrow measured wall gap may be either a doorway or a window.
 *
 * Window evidence comes from a dedicated double-line raster symbol. Door evidence becomes stronger
 * only after DoorSwingArcDetector recovers a real hinge/swing arc. A trusted swing therefore wins;
 * otherwise a strong window symbol can replace the geometry-only door candidate. Ambiguous evidence
 * fails closed to the original opening instead of emitting overlapping door+window meshes.
 *
 * A conflict is valid only when both observations occupy the same measured opening axis. Nearby
 * openings on crossing/perpendicular walls (for example a door next to a corner window) must never
 * erase one another merely because their centers are spatially close.
 */
internal object OpeningSemanticReconciler {

    fun reconcile(plan: FloorPlan): FloorPlan {
        if (plan.doors.isEmpty() || plan.windows.isEmpty()) return plan

        val retainedDoors = plan.doors.toMutableList()
        val retainedWindows = ArrayList<WindowOpening>()

        for (window in plan.windows.sortedByDescending { it.confidence }) {
            val conflicts = retainedDoors.filter { door -> overlapsSameOpening(door, window) }
            if (conflicts.isEmpty()) {
                retainedWindows += window
                continue
            }

            val trustedDoor = conflicts.maxByOrNull { doorEvidenceScore(it) }
            if (trustedDoor != null && trustedDoor.swingConfidence >= TRUSTED_SWING_CONFIDENCE) {
                // An actual swing arc is more specific than two parallel raster strokes.
                continue
            }

            val strongestDoorConfidence = conflicts.maxOfOrNull { it.confidence } ?: 0f
            val windowWins = window.confidence >= MIN_WINDOW_OVERRIDE_CONFIDENCE &&
                window.confidence + WINDOW_OVERRIDE_MARGIN >= strongestDoorConfidence

            if (windowWins) {
                retainedDoors.removeAll(conflicts.toSet())
                retainedWindows += window
            }
            // Otherwise keep the original door and discard the conflicting low-certainty window.
        }

        return plan.copy(
            doors = retainedDoors,
            windows = retainedWindows,
        )
    }

    private fun overlapsSameOpening(door: DoorOpening, window: WindowOpening): Boolean {
        if (axisAngleDifference(door.rotationDegrees, window.rotationDegrees) > MAX_CONFLICT_AXIS_DEGREES) {
            return false
        }

        val radians = door.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        val nx = -uz
        val nz = ux
        val dx = window.center.x - door.center.x
        val dz = window.center.z - door.center.z
        val alongDistance = abs(dx * ux + dz * uz)
        val perpendicularDistance = abs(dx * nx + dz * nz)

        val measuredWidthAgreement = abs(door.widthMeters - window.widthMeters) <= maxWidthDifference(door, window)
        return measuredWidthAgreement &&
            alongDistance <= MAX_CONFLICT_ALONG_OFFSET_METERS &&
            perpendicularDistance <= MAX_CONFLICT_PERPENDICULAR_OFFSET_METERS
    }

    private fun maxWidthDifference(door: DoorOpening, window: WindowOpening): Float =
        maxOf(MIN_WIDTH_DIFFERENCE_METERS, min(door.widthMeters, window.widthMeters) * WIDTH_DIFFERENCE_RATIO)

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

    private fun doorEvidenceScore(door: DoorOpening): Float =
        door.confidence * 0.58f + door.swingConfidence * 0.42f

    private const val TRUSTED_SWING_CONFIDENCE = 0.64f
    private const val MIN_WINDOW_OVERRIDE_CONFIDENCE = 0.72f
    private const val WINDOW_OVERRIDE_MARGIN = 0.035f
    private const val MAX_CONFLICT_AXIS_DEGREES = 14f
    private const val MAX_CONFLICT_ALONG_OFFSET_METERS = 0.28f
    private const val MAX_CONFLICT_PERPENDICULAR_OFFSET_METERS = 0.22f
    private const val MIN_WIDTH_DIFFERENCE_METERS = 0.22f
    private const val WIDTH_DIFFERENCE_RATIO = 0.28f
}
