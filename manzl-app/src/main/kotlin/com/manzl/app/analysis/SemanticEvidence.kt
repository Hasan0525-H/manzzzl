package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2

/**
 * Evidence contract shared by deterministic CV, bundled on-device AI and explicit user corrections.
 * Providers report observations; GeometryEvidenceFusion decides whether those observations are
 * geometrically plausible enough to enter the canonical FloorPlan.
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
    val confidence: Float,
    val source: EvidenceSource,
)

internal fun interface SemanticEvidenceProvider {
    suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence>
}
