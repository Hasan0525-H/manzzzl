package com.manzl.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowFrustumPlannerTest {

    @Test
    fun `small camera movement inside one texel keeps the same shadow focus`() {
        val first = ShadowFrustumPlanner.plan(
            focusX = 1.000f,
            focusY = 1.65f,
            focusZ = -2.000f,
            radiusMeters = 16f,
            mapSize = 1024,
        )
        val second = ShadowFrustumPlanner.plan(
            focusX = 1.006f,
            focusY = 1.65f,
            focusZ = -1.994f,
            radiusMeters = 16f,
            mapSize = 1024,
        )

        assertEquals(first.focusX, second.focusX, 0.000001f)
        assertEquals(first.focusZ, second.focusZ, 0.000001f)
    }

    @Test
    fun `texel world size matches ortho diameter divided by map size`() {
        val plan = ShadowFrustumPlanner.plan(
            focusX = 0f,
            focusY = 0f,
            focusZ = 0f,
            radiusMeters = 12f,
            mapSize = 1024,
        )

        assertEquals(24f / 1024f, plan.texelWorldSize, 0.000001f)
        assertEquals(12f, plan.radiusMeters, 0f)
    }

    @Test
    fun `focus snaps to an exact shadow texel`() {
        val plan = ShadowFrustumPlanner.plan(
            focusX = 3.14159f,
            focusY = 2f,
            focusZ = -6.28318f,
            radiusMeters = 15.5f,
            mapSize = 1024,
        )

        val xTexels = plan.focusX / plan.texelWorldSize
        val zTexels = plan.focusZ / plan.texelWorldSize
        assertTrue(kotlin.math.abs(xTexels - kotlin.math.round(xTexels)) < 0.0001f)
        assertTrue(kotlin.math.abs(zTexels - kotlin.math.round(zTexels)) < 0.0001f)
    }
}
