package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralContentBoundsTest {

    @Test
    fun `structural envelope excludes large asymmetric page margins`() {
        val points = listOf(
            220 to 160,
            780 to 160,
            220 to 640,
            780 to 640,
            220 to 300,
            780 to 300,
            400 to 160,
            400 to 640,
        )

        val bounds = StructuralContentBounds.fromPoints(
            imageWidth = 1200,
            imageHeight = 900,
            points = points,
        )

        assertTrue(bounds.left in 190..219)
        assertTrue(bounds.rightExclusive in 781..811)
        assertTrue(bounds.top in 130..159)
        assertTrue(bounds.bottomExclusive in 641..671)
        assertTrue(bounds.width < 700)
        assertTrue(bounds.height < 600)
    }

    @Test
    fun `insufficient structural evidence fails closed to full raster`() {
        val bounds = StructuralContentBounds.fromPoints(
            imageWidth = 1000,
            imageHeight = 700,
            points = listOf(300 to 300, 700 to 300),
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(1000, bounds.rightExclusive)
        assertEquals(700, bounds.bottomExclusive)
    }

    @Test
    fun `plan raster transform round trips through cropped structural content`() {
        val plan = FloorPlan(
            widthMeters = 20f,
            depthMeters = 10f,
            walls = emptyList(),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 500,
            contentLeftFraction = 0.20f,
            contentTopFraction = 0.10f,
            contentRightFraction = 0.80f,
            contentBottomFraction = 0.90f,
        )
        val transform = PlanRasterTransform.forImage(plan, 1000, 500)

        val centrePixel = transform.planToImage(Vec2(0f, 0f))
        assertEquals(500f, centrePixel.first, 0.001f)
        assertEquals(250f, centrePixel.second, 0.001f)

        val topLeft = transform.imageToPlan(200f, 50f)
        assertEquals(-10f, topLeft.x, 0.001f)
        assertEquals(-5f, topLeft.z, 0.001f)

        val point = Vec2(3.25f, -1.75f)
        val pixel = transform.planToImage(point)
        val roundTrip = transform.imageToPlan(pixel.first, pixel.second)
        assertEquals(point.x, roundTrip.x, 0.001f)
        assertEquals(point.z, roundTrip.z, 0.001f)
    }

    @Test
    fun `same structural content maps identically despite different white margins`() {
        val basePlan = FloorPlan(
            widthMeters = 18f,
            depthMeters = 12f,
            walls = emptyList(),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
            contentLeftFraction = 0.10f,
            contentTopFraction = 0.125f,
            contentRightFraction = 0.90f,
            contentBottomFraction = 0.875f,
        )
        val widerMarginPlan = basePlan.copy(
            sourceWidthPx = 1400,
            sourceHeightPx = 1000,
            contentLeftFraction = 0.21428572f,
            contentTopFraction = 0.20f,
            contentRightFraction = 0.78571427f,
            contentBottomFraction = 0.80f,
        )

        val point = Vec2(4.5f, 3f)
        val first = PlanRasterTransform.forImage(basePlan, 1000, 800)
        val second = PlanRasterTransform.forImage(widerMarginPlan, 1400, 1000)

        val firstPx = first.planToImage(point)
        val secondPx = second.planToImage(point)
        val firstRoundTrip = first.imageToPlan(firstPx.first, firstPx.second)
        val secondRoundTrip = second.imageToPlan(secondPx.first, secondPx.second)

        assertEquals(point.x, firstRoundTrip.x, 0.001f)
        assertEquals(point.z, firstRoundTrip.z, 0.001f)
        assertEquals(point.x, secondRoundTrip.x, 0.001f)
        assertEquals(point.z, secondRoundTrip.z, 0.001f)
    }
}
