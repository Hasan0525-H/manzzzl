package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2

/**
 * Evidence contract shared by deterministic CV, bundled on-device AI and explicit user corrections.
 * Providers report observations; GeometryEvidenceFusion decides whether those observations are
 * geometrically plausible enough to enter the canonical FloorPlan.
 *
 * [observerId] identifies the concrete independent expert that made an observation. This is distinct
 * from the broad [source] family: two separate CV algorithms are not one observation, and a distilled
 * reconstruction student must not be collapsed with a legacy local classifier merely because both
 * execute on-device. Consensus can therefore reward genuine cross-expert agreement without double
 * counting repeated hypotheses emitted by the same detector.
 */
internal enum class SemanticKind {
    DOOR,
    WINDOW,
    STAIR,
    ROOM,
}

internal enum class EvidenceSource {
    CLASSICAL_CV,
    LOCAL_AI,
    USER_CORRECTION,
}

internal data class SemanticEvidence(
    val kind: SemanticKind,
    val center: Vec2,
    val widthMeters: Float? = null,
    val lengthMeters: Float? = null,
    val rotationDegrees: Float? = null,
    val polygon: List<Vec2> = emptyList(),
    val label: String? = null,
    /** Optional directly observed repetition count, currently used for measured stair treads. */
    val countHint: Int? = null,
    val confidence: Float,
    val source: EvidenceSource,
    /** Stable detector identity for independence-aware consensus; null preserves legacy source-family semantics. */
    val observerId: String? = null,
)

internal fun interface SemanticEvidenceProvider {
    suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence>
}
