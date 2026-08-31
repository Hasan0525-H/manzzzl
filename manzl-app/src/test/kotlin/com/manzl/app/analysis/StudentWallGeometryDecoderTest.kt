package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class StudentWallGeometryDecoderTest {

    @Test
    fun `orientation head preserves diagonal wall geometry`() {
        val side = 64
        val plane = side * side
        val wall = BooleanArray(plane)
        val corners = FloatArray(plane)
        val ox = FloatArray(plane)
        val oy = FloatArray(plane)
        val inv = (1f / sqrt(2f))

        for (i in 10..52) {
            for (offset in -1..1) {
                val x = i
                val y = i + offset
                wall[y * side + x] = true
                ox[y * side + x] = inv
                oy[y * side + x] = inv
            }
        }
        corners[10 * side + 10] = 0.96f
        corners[52 * side + 52] = 0.94f

        val result = StudentWallGeometryDecoder.decode(
            wallMask = wall,
            cornerProbability = corners,
            orientationX = ox,
            orientationY = oy,
            side = side,
            minLengthPx = 30f,
        )

        assertTrue(result.isNotEmpty())
        val best = result.first()
        assertTrue(best.confidence > 0.75f)
        assertTrue(kotlin.math.abs((best.x1 - best.x0) - (best.y1 - best.y0)) < 5f)
    }

    @Test
    fun `isolated wall pixels do not become a vector wall`() {
        val side = 48
        val plane = side * side
        val wall = BooleanArray(plane)
        val corners = FloatArray(plane)
        val ox = FloatArray(plane)
        val oy = FloatArray(plane)
        wall[24 * side + 24] = true
        ox[24 * side + 24] = 1f

        val result = StudentWallGeometryDecoder.decode(
            wallMask = wall,
            cornerProbability = corners,
            orientationX = ox,
            orientationY = oy,
            side = side,
            minLengthPx = 18f,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `corner peaks snap traced endpoints`() {
        val side = 64
        val plane = side * side
        val wall = BooleanArray(plane)
        val corners = FloatArray(plane)
        val ox = FloatArray(plane)
        val oy = FloatArray(plane)

        for (x in 12..50) {
            for (offset in -1..1) {
                val y = 30 + offset
                wall[y * side + x] = true
                ox[y * side + x] = 1f
            }
        }
        corners[30 * side + 10] = 0.98f
        corners[30 * side + 52] = 0.97f

        val result = StudentWallGeometryDecoder.decode(
            wallMask = wall,
            cornerProbability = corners,
            orientationX = ox,
            orientationY = oy,
            side = side,
            minLengthPx = 24f,
        )

        assertEquals(1, result.size)
        val wallResult = result.single()
        assertTrue(kotlin.math.min(wallResult.x0, wallResult.x1) <= 12f)
        assertTrue(kotlin.math.max(wallResult.x0, wallResult.x1) >= 50f)
    }
}
