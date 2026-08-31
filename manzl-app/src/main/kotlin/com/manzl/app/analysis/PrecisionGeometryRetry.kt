package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus
import kotlin.math.max

/**
 * Memory-aware high-precision pass policy.
 *
 * When the source contains more pixels than the normal 2200px pass and heap headroom is safe, Manzl
 * performs a 2800/3200px extraction even if the normal pass already reports PASS. The normal geometry
 * is then re-verified against the same dense source evidence before either candidate is selected.
 * Thresholds are never lowered: this is additional evidence, not a route around the quality gate.
 */
internal object PrecisionGeometryRetryPolicy {

    fun analysisSideOrNull(
        sourceWidth: Int,
        sourceHeight: Int,
        maxHeapBytes: Long,
    ): Int? {
        val longest = max(sourceWidth, sourceHeight)
        if (longest <= PRIMARY_ANALYSIS_SIDE) return null

        val requested = when {
            maxHeapBytes >= LARGE_HEAP_BYTES -> HIGH_RETRY_SIDE
            maxHeapBytes >= MEDIUM_HEAP_BYTES -> MEDIUM_RETRY_SIDE
            else -> return null
        }
        return minOf(longest, requested).takeIf { it > PRIMARY_ANALYSIS_SIDE }
    }

    private const val PRIMARY_ANALYSIS_SIDE = 2200
    private const val MEDIUM_RETRY_SIDE = 2800
    private const val HIGH_RETRY_SIDE = 3200
    private const val MEDIUM_HEAP_BYTES = 320L * 1024L * 1024L
    private const val LARGE_HEAP_BYTES = 448L * 1024L * 1024L
}

/** Chooses candidates only after they have been judged on comparable independent fidelity evidence. */
internal object GeometryRetryChooser {

    fun choose(primary: FloorPlan, retry: FloorPlan): FloorPlan {
        val primaryRank = statusRank(primary.geometryFidelity.status)
        val retryRank = statusRank(retry.geometryFidelity.status)
        if (retryRank != primaryRank) return if (retryRank > primaryRank) retry else primary

        val p = primary.geometryFidelity
        val r = retry.geometryFidelity
        if (r.score != p.score) return if (r.score > p.score) retry else primary

        val primaryTie = p.wallCoverage * 0.45f + p.wallPrecision * 0.40f + p.endpointSupport * 0.15f
        val retryTie = r.wallCoverage * 0.45f + r.wallPrecision * 0.40f + r.endpointSupport * 0.15f
        return if (retryTie > primaryTie) retry else primary
    }

    private fun statusRank(status: GeometryFidelityStatus): Int = when (status) {
        GeometryFidelityStatus.PASS -> 3
        GeometryFidelityStatus.REVIEW_REQUIRED -> 2
        GeometryFidelityStatus.BLOCKED -> 1
        GeometryFidelityStatus.UNKNOWN -> 0
    }
}
