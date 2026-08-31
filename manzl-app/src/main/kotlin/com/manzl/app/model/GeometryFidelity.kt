package com.manzl.app.model

import androidx.compose.runtime.Immutable

/**
 * Independent quality report for the 2D -> geometry reconstruction stage.
 *
 * This is intentionally separate from semantic confidence. A model may be very sure that a symbol
 * is a door while the extracted wall geometry is still poor. The walkthrough is therefore gated by
 * geometry fidelity first, not by visual polish or semantic confidence.
 */
@Immutable
data class GeometryFidelityReport(
    val score: Float,
    val wallCoverage: Float,
    val wallPrecision: Float,
    val endpointSupport: Float,
    val status: GeometryFidelityStatus,
    /** Localized source-space regions that explain why aggregate fidelity is weak. */
    val issues: List<GeometryFidelityIssue> = emptyList(),
) {
    companion object {
        val UNKNOWN = GeometryFidelityReport(
            score = 0f,
            wallCoverage = 0f,
            wallPrecision = 0f,
            endpointSupport = 0f,
            status = GeometryFidelityStatus.UNKNOWN,
            issues = emptyList(),
        )
    }
}

@Immutable
data class GeometryFidelityIssue(
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val kind: GeometryFidelityIssueKind,
    val severity: Float,
)

@Immutable
enum class GeometryFidelityIssueKind {
    /** Source contains structural wall evidence that the reconstruction does not cover. */
    MISSING_SOURCE,

    /** Reconstruction places wall face pixels where source structural evidence is insufficient. */
    EXTRA_GEOMETRY,
}

enum class GeometryFidelityStatus {
    PASS,
    REVIEW_REQUIRED,
    BLOCKED,
    UNKNOWN,
}
