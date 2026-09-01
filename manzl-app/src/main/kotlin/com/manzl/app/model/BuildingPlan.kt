package com.manzl.app.model

import androidx.compose.runtime.Immutable

/**
 * Multi-level representation used when more than one architectural plan is available.
 *
 * FloorPlan remains the canonical single-image result and is intentionally unchanged. BuildingPlan
 * composes those results without allowing cross-floor semantics to rewrite any measured floor
 * geometry.
 */
@Immutable
data class FloorLevel(
    val id: String,
    val levelIndex: Int,
    val baseElevationMeters: Float,
    val plan: FloorPlan,
)

@Immutable
data class StairLevelLink(
    val lowerLevelId: String,
    val upperLevelId: String,
    val lowerStairIndex: Int,
    val upperStairIndex: Int,
    val confidence: Float,
)

@Immutable
enum class FloorRegistrationStatus {
    ALIGNED,
    REVIEW_REQUIRED,
    UNRESOLVED,
}

@Immutable
enum class FloorRegistrationEvidence {
    STAIR_SHAFT,
    FOOTPRINT_ONLY,
    NONE,
}

/**
 * Diagnostic only. suggestedOffsetX/Z describe how the upper drawing could be translated to align
 * with the lower drawing, but the renderer never applies this offset automatically. Any future
 * registration correction must be explicit/reviewable so source geometry is not silently warped.
 */
@Immutable
data class FloorRegistrationDiagnostic(
    val lowerLevelId: String,
    val upperLevelId: String,
    val status: FloorRegistrationStatus,
    val evidence: FloorRegistrationEvidence,
    val suggestedOffsetXMeters: Float = 0f,
    val suggestedOffsetZMeters: Float = 0f,
    val confidence: Float = 0f,
)

@Immutable
data class BuildingPlan(
    val levels: List<FloorLevel>,
    val stairLinks: List<StairLevelLink> = emptyList(),
    val registrationDiagnostics: List<FloorRegistrationDiagnostic> = emptyList(),
) {
    init {
        require(levels.map { it.id }.distinct().size == levels.size) {
            "Floor level ids must be unique"
        }
        require(levels.map { it.levelIndex }.distinct().size == levels.size) {
            "Floor level indices must be unique"
        }
    }

    companion object {
        fun singleLevel(plan: FloorPlan): BuildingPlan = BuildingPlan(
            levels = listOf(
                FloorLevel(
                    id = "level-0",
                    levelIndex = 0,
                    baseElevationMeters = 0f,
                    plan = plan,
                )
            )
        )
    }
}
