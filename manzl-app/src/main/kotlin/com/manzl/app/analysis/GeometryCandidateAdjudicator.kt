package com.manzl.app.analysis

import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import kotlin.math.max

/**
 * Shared acceptance policy for proposal-only geometry experts.
 *
 * Large plans make global coverage deltas tiny, so an important short partition can be a real local
 * repair while moving aggregate coverage by less than a few tenths of a percent. This adjudicator
 * therefore accepts either a clear global improvement or a measurable reduction in localized
 * missing-wall burden, while bounding precision/endpoint/extra-geometry regressions.
 */
internal object GeometryCandidateAdjudicator {

    enum class ChangeKind { ADDITION, REPLACEMENT }

    data class Decision(
        val accepted: Boolean,
        val globalImprovement: Boolean,
        val localRepair: Boolean,
        val missingBurdenReduction: Float,
        val extraBurdenIncrease: Float,
    )

    fun decide(
        before: GeometryFidelityReport,
        after: GeometryFidelityReport,
        kind: ChangeKind,
    ): Decision {
        val coverageGain = after.wallCoverage - before.wallCoverage
        val precisionLoss = before.wallPrecision - after.wallPrecision
        val scoreGain = after.score - before.score
        val endpointLoss = before.endpointSupport - after.endpointSupport
        val missingReduction = issueBurden(before, GeometryFidelityIssueKind.MISSING_SOURCE) -
            issueBurden(after, GeometryFidelityIssueKind.MISSING_SOURCE)
        val extraIncrease = issueBurden(after, GeometryFidelityIssueKind.EXTRA_GEOMETRY) -
            issueBurden(before, GeometryFidelityIssueKind.EXTRA_GEOMETRY)

        val global = when (kind) {
            ChangeKind.ADDITION ->
                coverageGain >= ADD_MIN_COVERAGE_GAIN &&
                    precisionLoss <= ADD_MAX_PRECISION_LOSS &&
                    scoreGain >= -ADD_MAX_SCORE_LOSS &&
                    endpointLoss <= MAX_ENDPOINT_LOSS

            ChangeKind.REPLACEMENT ->
                scoreGain >= REPLACE_MIN_SCORE_GAIN &&
                    coverageGain >= -REPLACE_MAX_COVERAGE_LOSS &&
                    precisionLoss <= REPLACE_MAX_PRECISION_LOSS &&
                    endpointLoss <= MAX_ENDPOINT_LOSS
        }

        val local = missingReduction >= MIN_LOCAL_MISSING_BURDEN_REDUCTION &&
            extraIncrease <= MAX_LOCAL_EXTRA_BURDEN_INCREASE &&
            precisionLoss <= LOCAL_MAX_PRECISION_LOSS &&
            scoreGain >= -LOCAL_MAX_SCORE_LOSS &&
            endpointLoss <= LOCAL_MAX_ENDPOINT_LOSS

        return Decision(
            accepted = global || local,
            globalImprovement = global,
            localRepair = local,
            missingBurdenReduction = missingReduction,
            extraBurdenIncrease = extraIncrease,
        )
    }

    private fun issueBurden(report: GeometryFidelityReport, kind: GeometryFidelityIssueKind): Float =
        report.issues.asSequence()
            .filter { it.kind == kind }
            .sumOf { issue ->
                val width = (issue.rightFraction - issue.leftFraction).coerceAtLeast(0f)
                val height = (issue.bottomFraction - issue.topFraction).coerceAtLeast(0f)
                (width * height * issue.severity.coerceIn(0f, 1f)).toDouble()
            }.toFloat()
            .coerceAtLeast(0f)

    private const val ADD_MIN_COVERAGE_GAIN = 0.0028f
    private const val ADD_MAX_PRECISION_LOSS = 0.008f
    private const val ADD_MAX_SCORE_LOSS = 0.0018f

    private const val REPLACE_MIN_SCORE_GAIN = 0.0012f
    private const val REPLACE_MAX_COVERAGE_LOSS = 0.004f
    private const val REPLACE_MAX_PRECISION_LOSS = 0.001f

    private const val MAX_ENDPOINT_LOSS = 0.030f

    // Local mismatch burden is source-normalized area * severity. A 3%×3% tile at severity 0.8
    // contributes 0.00072, so this threshold is intentionally able to rescue small but clear walls.
    private const val MIN_LOCAL_MISSING_BURDEN_REDUCTION = 0.00035f
    private const val MAX_LOCAL_EXTRA_BURDEN_INCREASE = 0.00018f
    private const val LOCAL_MAX_PRECISION_LOSS = 0.006f
    private const val LOCAL_MAX_SCORE_LOSS = 0.0022f
    private const val LOCAL_MAX_ENDPOINT_LOSS = 0.025f
}
