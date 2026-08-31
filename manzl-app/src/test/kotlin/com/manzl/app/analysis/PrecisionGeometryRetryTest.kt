package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PrecisionGeometryRetryTest {

    @Test
    fun `retry side is enabled only for large sources with safe heap headroom`() {
        assertNull(
            PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = 2000,
                sourceHeight = 1400,
                maxHeapBytes = 600L * 1024L * 1024L,
            )
        )
        assertNull(
            PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = 4200,
                sourceHeight = 3000,
                maxHeapBytes = 256L * 1024L * 1024L,
            )
        )
        assertEquals(
            2800,
            PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = 4200,
                sourceHeight = 3000,
                maxHeapBytes = 384L * 1024L * 1024L,
            )
        )
        assertEquals(
            3200,
            PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = 5000,
                sourceHeight = 3600,
                maxHeapBytes = 512L * 1024L * 1024L,
            )
        )
        assertEquals(
            2500,
            PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = 2500,
                sourceHeight = 1800,
                maxHeapBytes = 512L * 1024L * 1024L,
            )
        )
    }

    @Test
    fun `chooser always prefers a stronger fidelity status`() {
        val blocked = plan(GeometryFidelityStatus.BLOCKED, score = 0.69f)
        val review = plan(GeometryFidelityStatus.REVIEW_REQUIRED, score = 0.61f)
        val pass = plan(GeometryFidelityStatus.PASS, score = 0.75f)

        assertSame(review, GeometryRetryChooser.choose(blocked, review))
        assertSame(pass, GeometryRetryChooser.choose(review, pass))
        assertSame(pass, GeometryRetryChooser.choose(pass, blocked))
    }

    @Test
    fun `chooser uses independent score then component tie break within same status`() {
        val primary = plan(
            status = GeometryFidelityStatus.REVIEW_REQUIRED,
            score = 0.66f,
            coverage = 0.72f,
            precision = 0.73f,
            endpoints = 0.70f,
        )
        val betterScore = plan(
            status = GeometryFidelityStatus.REVIEW_REQUIRED,
            score = 0.69f,
            coverage = 0.70f,
            precision = 0.71f,
            endpoints = 0.68f,
        )
        assertSame(betterScore, GeometryRetryChooser.choose(primary, betterScore))

        val tiedScoreBetterComponents = plan(
            status = GeometryFidelityStatus.REVIEW_REQUIRED,
            score = 0.66f,
            coverage = 0.78f,
            precision = 0.77f,
            endpoints = 0.75f,
        )
        assertSame(tiedScoreBetterComponents, GeometryRetryChooser.choose(primary, tiedScoreBetterComponents))
    }

    private fun plan(
        status: GeometryFidelityStatus,
        score: Float,
        coverage: Float = score,
        precision: Float = score,
        endpoints: Float = score,
    ) = FloorPlan(
        widthMeters = 12f,
        depthMeters = 10f,
        walls = listOf(
            WallSegment(Vec2(-5f, -4f), Vec2(5f, -4f)),
            WallSegment(Vec2(5f, -4f), Vec2(5f, 4f)),
            WallSegment(Vec2(5f, 4f), Vec2(-5f, 4f)),
            WallSegment(Vec2(-5f, 4f), Vec2(-5f, -4f)),
        ),
        analysisConfidence = score,
        sourceWidthPx = 5000,
        sourceHeightPx = 3600,
        geometryFidelity = GeometryFidelityReport(
            score = score,
            wallCoverage = coverage,
            wallPrecision = precision,
            endpointSupport = endpoints,
            status = status,
        ),
    )
}
