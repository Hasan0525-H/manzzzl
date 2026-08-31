package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.WindowOpening
import kotlin.math.sqrt

/**
 * Resolves the one topology conflict that geometry-only gap detection cannot decide reliably:
 * a narrow wall gap may be either a doorway or a window.
 *
 * Window evidence comes from a dedicated double-line raster symbol. Door evidence becomes stronger
 * only after DoorSwingArcDetector recovers a real hinge/swing arc. A trusted swing therefore wins;
 * otherwise a strong window symbol can replace the geometry-only door candidate. Ambiguous evidence
 * fails closed to the original doorway candidate instead of emitting overlapping door+window meshes.
 */
internal object OpeningSemanticReconciler {

    fun reconcile(plan: FloorPlan): FloorPlan {
        if (plan.doors.isEmpty() || plan.windows.isEmpty()) return plan

        val retainedDoors = plan.doors.toMutableList()
        val retainedWindows = ArrayList<WindowOpening>()

        for (window in plan.windows.sortedByDescending { it.confidence }) {
            val conflicts = retainedDoors.filter { door -> overlaps(door, window) }
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

    private fun overlaps(door: DoorOpening, window: WindowOpening): Boolean {
        val dx = door.center.x - window.center.x
        val dz = door.center.z - window.center.z
        val centerDistance = sqrt(dx * dx + dz * dz)
        val allowed = (door.widthMeters + window.widthMeters) * 0.34f + OPENING_CONFLICT_MARGIN_METERS
        return centerDistance <= allowed
    }

    private fun doorEvidenceScore(door: DoorOpening): Float =
        door.confidence * 0.58f + door.swingConfidence * 0.42f

    private const val TRUSTED_SWING_CONFIDENCE = 0.64f
    private const val MIN_WINDOW_OVERRIDE_CONFIDENCE = 0.72f
    private const val WINDOW_OVERRIDE_MARGIN = 0.035f
    private const val OPENING_CONFLICT_MARGIN_METERS = 0.12f
}
