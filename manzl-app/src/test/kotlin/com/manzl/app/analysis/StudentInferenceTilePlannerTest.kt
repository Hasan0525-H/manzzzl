package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentInferenceTilePlannerTest {

    @Test
    fun `4k content gets global pass plus overlapping detail tiles`() {
        val regions = StudentInferenceTilePlanner.plan(
            imageWidth = 4000,
            imageHeight = 3000,
            contentBounds = PixelContentBounds(300, 200, 3700, 2800),
            maxDetailSidePx = 1200,
            overlapFraction = 0.25f,
            maxRegions = 13,
        )

        assertTrue(regions.size > 2)
        assertEquals(StudentInferenceTilePlanner.Region.Kind.GLOBAL, regions.first().kind)
        assertEquals(4000, regions.first().width)
        assertTrue(regions.drop(1).all { it.kind == StudentInferenceTilePlanner.Region.Kind.DETAIL })
        assertTrue(regions.drop(1).all { it.left >= 300 && it.rightExclusive <= 3700 })
        assertTrue(regions.drop(1).all { it.top >= 200 && it.bottomExclusive <= 2800 })
    }

    @Test
    fun `small plan avoids redundant detail inference`() {
        val regions = StudentInferenceTilePlanner.plan(
            imageWidth = 1000,
            imageHeight = 800,
            contentBounds = PixelContentBounds(40, 40, 960, 760),
            maxDetailSidePx = 1200,
        )

        assertEquals(1, regions.size)
        assertEquals(StudentInferenceTilePlanner.Region.Kind.GLOBAL, regions.single().kind)
    }

    @Test
    fun `region cap is deterministic`() {
        val first = StudentInferenceTilePlanner.plan(
            imageWidth = 6000,
            imageHeight = 4500,
            contentBounds = PixelContentBounds(200, 200, 5800, 4300),
            maxRegions = 7,
        )
        val second = StudentInferenceTilePlanner.plan(
            imageWidth = 6000,
            imageHeight = 4500,
            contentBounds = PixelContentBounds(200, 200, 5800, 4300),
            maxRegions = 7,
        )

        assertEquals(7, first.size)
        assertEquals(first, second)
    }
}
