package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OpenCvColumnExpertTest {

    @Test
    fun `oriented bounds preserve rotated rectangular column size and angle`() {
        val centerX = 120.0
        val centerY = 80.0
        val halfMajor = 18.0
        val halfMinor = 10.0
        val angle = 31.0 * PI / 180.0
        val ux = cos(angle)
        val uy = sin(angle)
        val nx = -uy
        val ny = ux

        fun point(along: Double, normal: Double) = Point(
            centerX + ux * along + nx * normal,
            centerY + uy * along + ny * normal,
        )
        val points = arrayOf(
            point(-halfMajor, -halfMinor),
            point(halfMajor, -halfMinor),
            point(halfMajor, halfMinor),
            point(-halfMajor, halfMinor),
        )

        val fitted = OpenCvColumnExpert.orientedBounds(points)
            ?: error("expected oriented fit")

        assertEquals(36f, fitted.majorSpan, 0.05f)
        assertEquals(20f, fitted.minorSpan, 0.05f)
        assertEquals(centerX, fitted.center.x, 0.05)
        assertEquals(centerY, fitted.center.y, 0.05)
        val normalized = ((fitted.rotationDegrees % 180f) + 180f) % 180f
        assertEquals(31f, normalized, 0.2f)
        assertEquals(720f, OpenCvColumnExpert.polygonArea(points), 0.1f)
    }

    @Test
    fun `degenerate contour is rejected`() {
        val points = arrayOf(
            Point(10.0, 10.0),
            Point(10.0, 10.0),
            Point(10.0, 10.0),
            Point(10.0, 10.0),
        )

        assertTrue(OpenCvColumnExpert.orientedBounds(points) == null)
    }
}
