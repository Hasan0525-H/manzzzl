package com.manzl.app.render

import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolygonHoleTriangulatorTest {

    @Test
    fun `central rectangular shaft is removed from floor surface`() {
        val outer = listOf(
            Vec2(-5f, -5f),
            Vec2(5f, -5f),
            Vec2(5f, 5f),
            Vec2(-5f, 5f),
        )
        val shaft = listOf(
            Vec2(-1f, -1f),
            Vec2(1f, -1f),
            Vec2(1f, 1f),
            Vec2(-1f, 1f),
        )

        val triangles = PolygonHoleTriangulator.triangulate(outer, listOf(shaft))

        assertTrue(triangles.isNotEmpty())
        val area = triangles.sumOf { triangleArea(it).toDouble() }.toFloat()
        assertTrue("area=$area", abs(area - 96f) <= 0.12f)
        assertTrue(
            triangles.none { triangle ->
                val centroid = Vec2(
                    (triangle.a.x + triangle.b.x + triangle.c.x) / 3f,
                    (triangle.a.z + triangle.b.z + triangle.c.z) / 3f,
                )
                centroid.x > -0.98f && centroid.x < 0.98f && centroid.z > -0.98f && centroid.z < 0.98f
            }
        )
    }

    @Test
    fun `hole crossing outer boundary fails closed`() {
        val outer = listOf(
            Vec2(-2f, -2f),
            Vec2(2f, -2f),
            Vec2(2f, 2f),
            Vec2(-2f, 2f),
        )
        val invalidHole = listOf(
            Vec2(1f, -0.5f),
            Vec2(3f, -0.5f),
            Vec2(3f, 0.5f),
            Vec2(1f, 0.5f),
        )

        assertEquals(emptyList<Triangle2>(), PolygonHoleTriangulator.triangulate(outer, listOf(invalidHole)))
    }

    private fun triangleArea(triangle: Triangle2): Float = kotlin.math.abs(
        (triangle.b.x - triangle.a.x) * (triangle.c.z - triangle.a.z) -
            (triangle.b.z - triangle.a.z) * (triangle.c.x - triangle.a.x)
    ) * 0.5f
}
