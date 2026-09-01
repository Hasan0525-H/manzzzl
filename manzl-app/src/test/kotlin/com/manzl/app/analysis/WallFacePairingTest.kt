package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class WallFacePairingTest {

    @Test
    fun `parallel wall faces produce centered measured wall`() {
        val result = WallFacePairing.pair(
            lines = listOf(
                WallFacePairing.PixelLine(10f, 20f, 210f, 20f),
                WallFacePairing.PixelLine(12f, 30f, 208f, 30f),
            ),
            minThicknessPx = 6f,
            maxThicknessPx = 20f,
            minOverlapPx = 80f,
        )

        assertEquals(1, result.size)
        val wall = result.single()
        assertEquals(10f, wall.thicknessPx, 0.25f)
        assertEquals(25f, (wall.y0 + wall.y1) * 0.5f, 0.25f)
        assertTrue(wall.overlapRatio > 0.95f)
    }

    @Test
    fun `diagonal parallel faces preserve free angle`() {
        val offset = 10f / sqrt(2f)
        val result = WallFacePairing.pair(
            lines = listOf(
                WallFacePairing.PixelLine(20f, 20f, 180f, 180f),
                WallFacePairing.PixelLine(20f - offset, 20f + offset, 180f - offset, 180f + offset),
            ),
            minThicknessPx = 7f,
            maxThicknessPx = 14f,
            minOverlapPx = 100f,
        )

        assertEquals(1, result.size)
        val wall = result.single()
        assertEquals(10f, wall.thicknessPx, 0.6f)
        assertTrue(wall.confidence > 0.9f)
    }

    @Test
    fun `dimension lines too far apart are rejected`() {
        val result = WallFacePairing.pair(
            lines = listOf(
                WallFacePairing.PixelLine(0f, 20f, 240f, 20f),
                WallFacePairing.PixelLine(0f, 80f, 240f, 80f),
            ),
            minThicknessPx = 5f,
            maxThicknessPx = 24f,
            minOverlapPx = 80f,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `short incidental overlap does not become wall pair`() {
        val result = WallFacePairing.pair(
            lines = listOf(
                WallFacePairing.PixelLine(0f, 20f, 220f, 20f),
                WallFacePairing.PixelLine(180f, 30f, 250f, 30f),
            ),
            minThicknessPx = 6f,
            maxThicknessPx = 16f,
            minOverlapPx = 60f,
        )

        assertTrue(result.isEmpty())
    }
}
