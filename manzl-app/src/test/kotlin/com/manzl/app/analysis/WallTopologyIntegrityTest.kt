package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallTopologyIntegrityTest {

    @Test
    fun `detects trusted T junction that leaves a real physical crack`() {
        val plan = plan(
            walls = listOf(
                wall(Vec2(-3f, 0f), Vec2(3f, 0f), thickness = 0.18f),
                wall(Vec2(0f, 3f), Vec2(0f, 0.26f), thickness = 0.18f),
            )
        )

        val issues = WallTopologyIntegrity.findNearMissJunctions(plan)

        assertTrue(issues.isNotEmpty())
        assertTrue(issues.first().physicalGapMeters in 0.04f..0.08f)
        assertFalse(GeometryQualityGate.isReadyFor3d(plan))
        assertTrue(GeometryQualityGate.rejectionMessageArabic(plan)!!.contains("وصلة جدران"))
    }

    @Test
    fun `endpoint that physically overlaps target wall is not a near miss`() {
        val plan = plan(
            walls = listOf(
                wall(Vec2(-3f, 0f), Vec2(3f, 0f), thickness = 0.20f),
                wall(Vec2(0f, 3f), Vec2(0f, 0.17f), thickness = 0.18f),
            )
        )

        assertTrue(WallTopologyIntegrity.findNearMissJunctions(plan).isEmpty())
        assertTrue(GeometryQualityGate.isReadyFor3d(plan))
    }

    @Test
    fun `collinear opening gap is ignored because it may be a door or window`() {
        val plan = plan(
            walls = listOf(
                wall(Vec2(-3f, 0f), Vec2(-0.5f, 0f)),
                wall(Vec2(0.5f, 0f), Vec2(3f, 0f)),
            )
        )

        assertTrue(WallTopologyIntegrity.findNearMissJunctions(plan).isEmpty())
        assertTrue(GeometryQualityGate.isReadyFor3d(plan))
    }

    @Test
    fun `nearby target endpoint is not guessed into a corner connection`() {
        val plan = plan(
            walls = listOf(
                wall(Vec2(-3f, 0f), Vec2(0f, 0f)),
                wall(Vec2(0.24f, 0.20f), Vec2(0.24f, 2.5f)),
            )
        )

        assertTrue(WallTopologyIntegrity.findNearMissJunctions(plan).isEmpty())
    }

    @Test
    fun `weak wall candidate cannot block otherwise verified geometry`() {
        val plan = plan(
            walls = listOf(
                wall(Vec2(-3f, 0f), Vec2(3f, 0f), confidence = 0.94f),
                wall(Vec2(0f, 3f), Vec2(0f, 0.26f), confidence = 0.55f),
            )
        )

        assertTrue(WallTopologyIntegrity.findNearMissJunctions(plan).isEmpty())
        assertTrue(GeometryQualityGate.isReadyFor3d(plan))
    }

    private fun wall(
        start: Vec2,
        end: Vec2,
        thickness: Float = 0.18f,
        confidence: Float = 0.92f,
    ) = WallSegment(
        start = start,
        end = end,
        thicknessMeters = thickness,
        confidence = confidence,
    )

    private fun plan(walls: List<WallSegment>) = FloorPlan(
        widthMeters = 8f,
        depthMeters = 8f,
        walls = walls,
        analysisConfidence = 0.94f,
        sourceWidthPx = 1200,
        sourceHeightPx = 1200,
        geometryFidelity = GeometryFidelityReport(
            score = 0.92f,
            wallCoverage = 0.91f,
            wallPrecision = 0.93f,
            endpointSupport = 0.90f,
            status = GeometryFidelityStatus.PASS,
        ),
    )
}
