package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredOpeningGapDetectorTest {

    @Test
    fun `finds horizontal measured gap`() {
        val gaps = MeasuredOpeningGapDetector.detect(
            listOf(
                wall(Vec2(-3f, -2f), Vec2(-0.5f, -2f)),
                wall(Vec2(0.5f, -2f), Vec2(3f, -2f)),
            )
        )

        assertEquals(1, gaps.size)
        val gap = gaps.first()
        assertEquals(0f, gap.center.x, 0.001f)
        assertEquals(-2f, gap.center.z, 0.001f)
        assertEquals(1f, gap.widthMeters, 0.001f)
        assertEquals(0f, gap.rotationDegrees, 0.01f)
    }

    @Test
    fun `finds diagonal measured gap without snapping to axis`() {
        val gaps = MeasuredOpeningGapDetector.detect(
            listOf(
                wall(Vec2(-3f, -3f), Vec2(-0.5f, -0.5f)),
                wall(Vec2(0.5f, 0.5f), Vec2(3f, 3f)),
            )
        )

        assertEquals(1, gaps.size)
        val gap = gaps.first()
        assertEquals(0f, gap.center.x, 0.01f)
        assertEquals(0f, gap.center.z, 0.01f)
        assertEquals(1.414f, gap.widthMeters, 0.03f)
        assertEquals(45f, gap.rotationDegrees, 0.2f)
    }

    @Test
    fun `continuous wall does not create an opening proposal`() {
        val gaps = MeasuredOpeningGapDetector.detect(
            listOf(
                wall(Vec2(-3f, 0f), Vec2(3f, 0f)),
                wall(Vec2(-3f, 2f), Vec2(3f, 2f)),
            )
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `parallel but spatially separate wall lines do not form one gap`() {
        val gaps = MeasuredOpeningGapDetector.detect(
            listOf(
                wall(Vec2(-3f, 0f), Vec2(-0.5f, 0f)),
                wall(Vec2(0.5f, 0.5f), Vec2(3f, 0.5f)),
            )
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `weak fragments are not opening geometry authority`() {
        val gaps = MeasuredOpeningGapDetector.detect(
            listOf(
                wall(Vec2(-3f, 0f), Vec2(-0.5f, 0f), confidence = 0.50f),
                wall(Vec2(0.5f, 0f), Vec2(3f, 0f), confidence = 0.95f),
            )
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `opening width bounds are enforced by proposal caller`() {
        val walls = listOf(
            wall(Vec2(-3f, 0f), Vec2(-1f, 0f)),
            wall(Vec2(1f, 0f), Vec2(3f, 0f)),
        )

        assertEquals(
            1,
            MeasuredOpeningGapDetector.detect(walls, minWidthMeters = 1.8f, maxWidthMeters = 2.2f).size,
        )
        assertTrue(
            MeasuredOpeningGapDetector.detect(walls, minWidthMeters = 0.6f, maxWidthMeters = 1.5f).isEmpty()
        )
    }

    private fun wall(
        start: Vec2,
        end: Vec2,
        confidence: Float = 0.92f,
    ) = WallSegment(
        start = start,
        end = end,
        confidence = confidence,
    )
}
