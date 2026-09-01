package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralTopologyReconcilerTest {

    @Test
    fun `tiny collinear scan gap is repaired`() {
        val result = StructuralTopologyReconciler.reconcile(
            listOf(
                horizontal(-2f, -0.08f, 0f),
                horizontal(0.08f, 2f, 0f),
            )
        )

        assertEquals(1, result.size)
        assertTrue(kotlin.math.abs(result.single().start.x + 2f) < 0.02f)
        assertTrue(kotlin.math.abs(result.single().end.x - 2f) < 0.02f)
    }

    @Test
    fun `door sized collinear gap is preserved`() {
        val result = StructuralTopologyReconciler.reconcile(
            listOf(
                horizontal(-2f, -0.45f, 0f),
                horizontal(0.45f, 2f, 0f),
            )
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `near miss endpoint snaps to perpendicular junction`() {
        val result = StructuralTopologyReconciler.reconcile(
            listOf(
                horizontal(-2f, -0.12f, 0f),
                vertical(0f, -1.5f, 1.5f),
            )
        )

        val horizontal = result.first { kotlin.math.abs(it.start.z - it.end.z) < 0.01f }
        assertTrue(kotlin.math.abs(horizontal.end.x) < 0.02f)
    }

    private fun horizontal(x0: Float, x1: Float, z: Float) =
        WallSegment(start = Vec2(x0, z), end = Vec2(x1, z), thicknessMeters = 0.18f)

    private fun vertical(x: Float, z0: Float, z1: Float) =
        WallSegment(start = Vec2(x, z0), end = Vec2(x, z1), thicknessMeters = 0.18f)
}
