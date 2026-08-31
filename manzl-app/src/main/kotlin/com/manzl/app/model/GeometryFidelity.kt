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
) {
    companion object {
        val UNKNOWN = GeometryFidelityReport(
            score = 0f,
            wallCoverage = 0f,
            wallPrecision = 0f,
            endpointSupport = 0f,
            status = GeometryFidelityStatus.UNKNOWN,
        )
    }
}

enum class GeometryFidelityStatus {
    PASS,
    REVIEW_REQUIRED,
    BLOCKED,
    UNKNOWN,
}
