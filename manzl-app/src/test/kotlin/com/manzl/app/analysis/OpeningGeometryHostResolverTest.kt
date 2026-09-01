package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGeometryHostResolverTest {

    @Test
    fun `compatible diagonal wall runs resolve measured opening geometry`() {
        val host = OpeningGeometryHostResolver.resolve(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, -3f),
                    end = Vec2(-0.5f, -0.5f),
                    thicknessMeters = 0.18f,
                    confidence = 0.93f,
                ),
                WallSegment(
                    start = Vec2(0.5f, 0.5f),
                    end = Vec2(3f, 3f),
                    thicknessMeters = 0.20f,
                    confidence = 0.92f,
                ),
            ),
            candidateCenter = Vec2(0.05f, -0.04f),
            candidateWidthMeters = 1.40f,
            candidateRotationDegrees = 46f,
        )

        requireNotNull(host)
        assertEquals(45f, host.rotationDegrees, 0.25f)
        assertEquals(1.414f, host.widthMeters, 0.03f)
        assertTrue(kotlin.math.abs(host.center.x) < 0.02f)
        assertTrue(kotlin.math.abs(host.center.z) < 0.02f)
    }

    @Test
    fun `semantic evidence cannot bridge strongly incompatible wall thicknesses`() {
        val host = OpeningGeometryHostResolver.resolve(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, 0f),
                    end = Vec2(-0.5f, 0f),
                    thicknessMeters = 0.10f,
                    confidence = 0.95f,
                ),
                WallSegment(
                    start = Vec2(0.5f, 0f),
                    end = Vec2(3f, 0f),
                    thicknessMeters = 0.34f,
                    confidence = 0.96f,
                ),
            ),
            candidateCenter = Vec2(0f, 0f),
            candidateWidthMeters = 1f,
            candidateRotationDegrees = 0f,
        )

        assertNull(host)
    }
}
