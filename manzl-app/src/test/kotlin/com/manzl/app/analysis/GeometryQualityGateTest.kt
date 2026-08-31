package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
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

    private fun plan(status: GeometryFidelityStatus, score: Float) = FloorPlan(
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
        ),
    )
}
