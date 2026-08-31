package com.manzl.app.render

import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolygonTriangulatorTest {

    @Test
    fun `triangulates an L shaped room without filling the missing corner`() {
        val polygon = listOf(
            Vec2(0f, 0f),
            Vec2(4f, 0f),
            Vec2(4f, 2f),
            Vec2(2f, 2f),
            Vec2(2f, 4f),
            Vec2(0f, 4f),
        )

        val triangles = PolygonTriangulator.triangulate(polygon)

        assertEquals(4, triangles.size)
        val polygonArea = PolygonTriangulator.polygonArea(polygon)
        val triangleArea = triangles.sumOf { triangle ->
            area(triangle.a, triangle.b, triangle.c).toDouble()
        }.toFloat()
        assertTrue(abs(polygonArea - triangleArea) < 0.001f)
        assertEquals(12f, polygonArea, 0.001f)
    }

    @Test
    fun `accepts clockwise simple polygons`() {
        val polygon = listOf(
            Vec2(0f, 3f),
            Vec2(3f, 3f),
            Vec2(3f, 0f),
            Vec2(0f, 0f),
        )

        val triangles = PolygonTriangulator.triangulate(polygon)

        assertEquals(2, triangles.size)
        assertEquals(9f, PolygonTriangulator.polygonArea(polygon), 0.001f)
    }

    @Test
    fun `removes duplicate closing point and collinear vertices`() {
        val polygon = listOf(
            Vec2(0f, 0f),
            Vec2(2f, 0f),
            Vec2(4f, 0f),
            Vec2(4f, 3f),
            Vec2(0f, 3f),
            Vec2(0f, 0f),
        )

        val triangles = PolygonTriangulator.triangulate(polygon)

        assertEquals(2, triangles.size)
        assertEquals(12f, PolygonTriangulator.polygonArea(polygon), 0.001f)
    }

    @Test
    fun `fails closed for degenerate polygon`() {
        val polygon = listOf(
            Vec2(0f, 0f),
            Vec2(1f, 0f),
            Vec2(2f, 0f),
        )

        assertTrue(PolygonTriangulator.triangulate(polygon).isEmpty())
    }

    private fun area(a: Vec2, b: Vec2, c: Vec2): Float =
        abs((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)) * 0.5f
}
