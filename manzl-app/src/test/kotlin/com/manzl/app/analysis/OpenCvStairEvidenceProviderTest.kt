package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OpenCvStairEvidenceProviderTest {

    @Test
    fun `regular 32 degree tread band is recovered as one stair candidate`() {
        val angle = 32.0 * PI / 180.0
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val nx = -uy
        val ny = ux
        val centerX = 320f
        val centerY = 250f
        val treadLength = 88f
        val spacing = 11f
        val count = 11

        val lines = buildList {
            for (index in 0 until count) {
                val offset = (index - (count - 1) * 0.5f) * spacing
                val cx = centerX + nx * offset
                val cy = centerY + ny * offset
                add(
                    ArbitraryAngleStairDetector.RasterLine(
                        x0 = cx - ux * treadLength * 0.5f,
                        y0 = cy - uy * treadLength * 0.5f,
                        x1 = cx + ux * treadLength * 0.5f,
                        y1 = cy + uy * treadLength * 0.5f,
                    )
                )
                // Simulate the second Canny edge of a thick tread. It must not double the step count.
                add(
                    ArbitraryAngleStairDetector.RasterLine(
                        x0 = cx + nx * 1.0f - ux * treadLength * 0.5f,
                        y0 = cy + ny * 1.0f - uy * treadLength * 0.5f,
                        x1 = cx + nx * 1.0f + ux * treadLength * 0.5f,
                        y1 = cy + ny * 1.0f + uy * treadLength * 0.5f,
                    )
                )
            }
        }

        val candidates = ArbitraryAngleStairDetector.detect(lines, imageWidth = 700, imageHeight = 600)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(centerX, candidate.centerX, 3.0f)
        assertEquals(centerY, candidate.centerY, 3.0f)
        assertEquals(treadLength, candidate.treadLengthPx, 3.0f)
        assertEquals(count, candidate.treadCount)
        assertEquals(32f, candidate.treadAngleDegrees, 1.0f)
        assertTrue(candidate.runLengthPx > 100f)
        assertTrue(candidate.confidence >= 0.74f)
    }

    @Test
    fun `irregular parallel drafting lines are rejected as stairs`() {
        val lines = listOf(
            line(100f, 100f, 82f, 0f),
            line(100f, 108f, 82f, 0f),
            line(100f, 129f, 82f, 0f),
            line(100f, 135f, 55f, 0f),
            line(100f, 170f, 82f, 0f),
            line(100f, 173f, 120f, 0f),
            line(100f, 210f, 82f, 0f),
        )

        val candidates = ArbitraryAngleStairDetector.detect(lines, imageWidth = 600, imageHeight = 500)

        assertTrue(candidates.isEmpty())
    }

    private fun line(cx: Float, cy: Float, length: Float, angleDegrees: Float): ArbitraryAngleStairDetector.RasterLine {
        val radians = angleDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uy = sin(radians)
        return ArbitraryAngleStairDetector.RasterLine(
            x0 = cx - ux * length * 0.5f,
            y0 = cy - uy * length * 0.5f,
            x1 = cx + ux * length * 0.5f,
            y1 = cy + uy * length * 0.5f,
        )
    }
}
