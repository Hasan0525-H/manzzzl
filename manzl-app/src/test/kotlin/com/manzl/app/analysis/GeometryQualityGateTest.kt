package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssue
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryQualityGateTest {

    @Test
    fun `only independently verified pass is ready for 3d`() {
        val pass = plan(GeometryFidelityStatus.PASS, 0.86f)
        val review = plan(GeometryFidelityStatus.REVIEW_REQUIRED, 0.66f)
        val blocked = plan(GeometryFidelityStatus.BLOCKED, 0.31f)
        val unknown = plan(GeometryFidelityStatus.UNKNOWN, 0f)

        assertTrue(GeometryQualityGate.isReadyFor3d(pass))
        assertNull(GeometryQualityGate.rejectionMessageArabic(pass))
        assertFalse(GeometryQualityGate.isReadyFor3d(review))
        assertFalse(GeometryQualityGate.isReadyFor3d(blocked))
        assertFalse(GeometryQualityGate.isReadyFor3d(unknown))
        assertTrue(GeometryQualityGate.rejectionMessageArabic(review)!!.contains("أوقفت بناء 3D"))
        assertTrue(GeometryQualityGate.rejectionMessageArabic(blocked)!!.contains("لا تطابق المخطط"))
        assertTrue(GeometryQualityGate.rejectionMessageArabic(unknown)!!.contains("لم تُتحقق"))
    }

    @Test
    fun `aggregate pass is rejected when one meaningful region has severe missing wall evidence`() {
        val passWithHiddenLocalFailure = plan(
            status = GeometryFidelityStatus.PASS,
            score = 0.91f,
            issues = listOf(
                GeometryFidelityIssue(
                    leftFraction = 0.10f,
                    topFraction = 0.20f,
                    rightFraction = 0.22f,
                    bottomFraction = 0.32f,
                    kind = GeometryFidelityIssueKind.MISSING_SOURCE,
                    severity = 0.71f,
                )
            ),
        )

        assertFalse(GeometryQualityGate.isReadyFor3d(passWithHiddenLocalFailure))
        val message = GeometryQualityGate.rejectionMessageArabic(passWithHiddenLocalFailure)
        assertTrue(message!!.contains("خطأ هندسياً موضعياً"))
        assertTrue(message.contains("مفقود"))
    }

    @Test
    fun `small or moderate localized noise does not override a clean aggregate pass`() {
        val moderate = plan(
            status = GeometryFidelityStatus.PASS,
            score = 0.90f,
            issues = listOf(
                GeometryFidelityIssue(
                    leftFraction = 0.10f,
                    topFraction = 0.10f,
                    rightFraction = 0.20f,
                    bottomFraction = 0.20f,
                    kind = GeometryFidelityIssueKind.MISSING_SOURCE,
                    severity = 0.49f,
                )
            ),
        )
        val tiny = plan(
            status = GeometryFidelityStatus.PASS,
            score = 0.90f,
            issues = listOf(
                GeometryFidelityIssue(
                    leftFraction = 0.10f,
                    topFraction = 0.10f,
                    rightFraction = 0.12f,
                    bottomFraction = 0.12f,
                    kind = GeometryFidelityIssueKind.EXTRA_GEOMETRY,
                    severity = 0.95f,
                )
            ),
        )

        assertTrue(GeometryQualityGate.isReadyFor3d(moderate))
        assertNull(GeometryQualityGate.rejectionMessageArabic(moderate))
        assertTrue(GeometryQualityGate.isReadyFor3d(tiny))
        assertNull(GeometryQualityGate.rejectionMessageArabic(tiny))
    }

    @Test
    fun `review copy preserves a clean pass without changing metrics`() {
        val original = plan(GeometryFidelityStatus.PASS, 0.90f)

        val review = GeometryQualityGate.planForReview(original)

        assertTrue(GeometryQualityGate.isReadyFor3d(original))
        assertEquals(GeometryFidelityStatus.PASS, review.geometryFidelity.status)
        assertEquals(original.geometryFidelity.score, review.geometryFidelity.score, 0f)
        assertEquals(original.walls, review.walls)
    }

    @Test
    fun `review copy downgrades hidden local gate failure while canonical plan remains pass`() {
        val original = plan(
            status = GeometryFidelityStatus.PASS,
            score = 0.93f,
            issues = listOf(
                GeometryFidelityIssue(
                    leftFraction = 0.14f,
                    topFraction = 0.18f,
                    rightFraction = 0.28f,
                    bottomFraction = 0.34f,
                    kind = GeometryFidelityIssueKind.MISSING_SOURCE,
                    severity = 0.76f,
                )
            ),
        )

        val review = GeometryQualityGate.planForReview(original)

        assertFalse(GeometryQualityGate.isReadyFor3d(original))
        assertEquals(GeometryFidelityStatus.PASS, original.geometryFidelity.status)
        assertEquals(GeometryFidelityStatus.REVIEW_REQUIRED, review.geometryFidelity.status)
        assertEquals(original.geometryFidelity.score, review.geometryFidelity.score, 0f)
        assertEquals(original.geometryFidelity.issues, review.geometryFidelity.issues)
        assertEquals(original.walls, review.walls)
    }

    @Test
    fun `review copy exposes a topology near miss as review required`() {
        val original = plan(GeometryFidelityStatus.PASS, 0.94f).copy(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, 0f),
                    end = Vec2(-0.28f, 0f),
                    thicknessMeters = 0.18f,
                    confidence = 0.96f,
                ),
                WallSegment(
                    start = Vec2(0f, -2f),
                    end = Vec2(0f, 2f),
                    thicknessMeters = 0.18f,
                    confidence = 0.97f,
                ),
                WallSegment(Vec2(-3f, -2f), Vec2(-3f, 2f), confidence = 0.95f),
                WallSegment(Vec2(-3f, -2f), Vec2(0f, -2f), confidence = 0.95f),
            )
        )

        assertTrue(WallTopologyIntegrity.findNearMissJunctions(original).isNotEmpty())
        assertFalse(GeometryQualityGate.isReadyFor3d(original))
        assertEquals(
            GeometryFidelityStatus.REVIEW_REQUIRED,
            GeometryQualityGate.planForReview(original).geometryFidelity.status,
        )
        assertEquals(GeometryFidelityStatus.PASS, original.geometryFidelity.status)
    }

    private fun plan(
        status: GeometryFidelityStatus,
        score: Float,
        issues: List<GeometryFidelityIssue> = emptyList(),
    ) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 10f,
        walls = listOf(
            WallSegment(Vec2(-4f, -4f), Vec2(4f, -4f)),
            WallSegment(Vec2(4f, -4f), Vec2(4f, 4f)),
            WallSegment(Vec2(4f, 4f), Vec2(-4f, 4f)),
            WallSegment(Vec2(-4f, 4f), Vec2(-4f, -4f)),
        ),
        analysisConfidence = score,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
        geometryFidelity = GeometryFidelityReport(
            score = score,
            wallCoverage = score,
            wallPrecision = score,
            endpointSupport = score,
            status = status,
            issues = issues,
        ),
    )
}
