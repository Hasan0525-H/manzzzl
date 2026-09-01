package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.FloorPlan

/**
 * Final fail-closed boundary between topology helpers and user-visible 3D.
 *
 * A measured opening-sized wall gap is useful internally for room closure and symbol search, but its
 * width alone does not prove that the opening is a door. Geometry-only candidates therefore remain
 * available during topology reconstruction and are stripped before the final [FloorPlan] is exposed
 * to rendering, collision door dynamics, façade joinery or the review overlay.
 */
internal object DoorPresentationPolicy {

    fun stripUnclassifiedGaps(plan: FloorPlan): FloorPlan {
        val confirmed = plan.doors.filter { door ->
            door.evidenceKind != DoorEvidenceKind.MEASURED_GAP
        }
        return if (confirmed.size == plan.doors.size) plan else plan.copy(doors = confirmed)
    }
}
