package com.manzl.app.analysis

import com.manzl.app.model.GeometryFidelityIssue
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryCandidateAdjudicatorTest {

    @Test
    fun `small local missing wall repair can pass without large global coverage jump`() {
        val before = report(
            score = 0.90f,
            coverage = 0.88f,
            precision = 0.94f,
            issues = listOf(issue(0.20f, 0.20f, 0.25f, 0.25f, GeometryFidelityIssueKind.MISSING_SOURCE, 0.90f)),
        )
        val after = report(
            score = 0.9005f,
            coverage = 0.881f,
            precision = 0.938f,
            issues = listOf(issue(0.20f, 0.20f, 0.25f, 0.25f, GeometryFidelityIssueKind.MISSING_SOURCE, 0.20f)),
        )

        val decision = GeometryCandidateAdjudicator.decide(
            before,
            after,
            GeometryCandidateAdjudicator.ChangeKind.ADDITION,
        )

        assertTrue(decision.accepted)
        assertTrue(decision.localRepair)
    }

    @Test
    fun `candidate that trades missing wall for extra geometry is rejected`() {
        val before = report(
            score = 0.90f,
            coverage = 0.88f,
            precision = 0.94f,
            issues = listOf(issue(0.20f, 0.20f, 0.25f, 0.25f, GeometryFidelityIssueKind.MISSING_SOURCE, 0.90f)),
        )
        val after = report(
            score = 0.898f,
            coverage = 0.881f,
            precision = 0.925f,
            issues = listOf(
                issue(0.20f, 0.20f, 0.25f, 0.25f, GeometryFidelityIssueKind.MISSING_SOURCE, 0.20f),
                issue(0.30f, 0.30f, 0.36f, 0.36f, GeometryFidelityIssueKind.EXTRA_GEOMETRY, 0.90f),
            ),
        )

        val decision = GeometryCandidateAdjudicator.decide(
            before,
            after,
            GeometryCandidateAdjudicator.ChangeKind.ADDITION,
        )

        assertFalse(decision.accepted)
    }

    private fun report(
        score: Float,
        coverage: Float,
        precision: Float,
        issues: List<GeometryFidelityIssue>,
    ) = GeometryFidelityReport(
        score = score,
        wallCoverage = coverage,
        wallPrecision = precision,
        endpointSupport = 0.90f,
        status = GeometryFidelityStatus.REVIEW_REQUIRED,
        issues = issues,
    )

    private fun issue(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        kind: GeometryFidelityIssueKind,
        severity: Float,
    ) = GeometryFidelityIssue(left, top, right, bottom, kind, severity)
}
