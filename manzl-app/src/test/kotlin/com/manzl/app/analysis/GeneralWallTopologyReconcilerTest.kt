package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GeneralWallTopologyReconcilerTest {

    @Test
    fun `arbitrary angle T junction snaps only short endpoint to measured intersection`() {
        val diagonal = wall(-2f, -2f, 2f, 2f)
        val branch = wall(-1.08f, 1f, 0.94f, 1f)

        val result = GeneralWallTopologyReconciler.reconcile(listOf(diagonal, branch))

        assertEquals(2, result.size)
        val horizontal = result.first { abs(it.start.z - it.end.z) < 0.001f }
        // z=1 intersects x=1 on the 45-degree wall. The branch is 6 cm short and may extend only
        // to that mathematical intersection; the diagonal remains unchanged.
        assertTrue(distance(horizontal.start, Vec2(1f, 1f)) < 0.001f || distance(horizontal.end, Vec2(1f, 1f)) < 0.001f)
        val recoveredDiagonal = result.first { abs(it.start.z - it.end.z) > 1f }
        assertTrue(distance(recoveredDiagonal.start, Vec2(-2f, -2f)) < 0.001f)
        assertTrue(distance(recoveredDiagonal.end, Vec2(2f, 2f)) < 0.001f)
    }

    @Test
    fun `door sized collinear gap is never bridged`() {
        val left = wall(-3f, 0f, -0.55f, 0f)
        val right = wall(0.55f, 0f, 3f, 0f)

        val result = GeneralWallTopologyReconciler.reconcile(listOf(left, right))

        assertEquals(2, result.size)
        val ordered = result.sortedBy { it.start.x }
        val gap = ordered[1].start.x - ordered[0].end.x
        assertEquals(1.10f, gap, 0.001f)
    }

    @Test
    fun `overlapping duplicate diagonal centerlines collapse without axis projection`() {
        val strong = WallSegment(
            start = Vec2(-2f, -1f),
            end = Vec2(2f, 1f),
            thicknessMeters = 0.20f,
            confidence = 0.94f,
        )
        val duplicate = WallSegment(
            start = Vec2(-1.5f, -0.75f),
            end = Vec2(2.5f, 1.25f),
            thicknessMeters = 0.19f,
            confidence = 0.82f,
        )

        val result = GeneralWallTopologyReconciler.reconcile(listOf(strong, duplicate))

        assertEquals(1, result.size)
        val merged = result.single()
        assertTrue(distance(merged.start, Vec2(-2f, -1f)) < 0.02f)
        assertTrue(distance(merged.end, Vec2(2.5f, 1.25f)) < 0.02f)
        val slope = (merged.end.z - merged.start.z) / (merged.end.x - merged.start.x)
        assertEquals(0.5f, slope, 0.01f)
    }

    @Test
    fun `parallel wall faces remain distinct`() {
        val faceA = wall(-2f, 0f, 2f, 0f, thickness = 0.18f)
        val faceB = wall(-2f, 0.18f, 2f, 0.18f, thickness = 0.18f)

        val result = GeneralWallTopologyReconciler.reconcile(listOf(faceA, faceB))

        assertEquals(2, result.size)
    }

    @Test
    fun `near corner snaps both arbitrary angle endpoints when both axes support intersection`() {
        val a = wall(-2f, -1f, -0.04f, -0.02f)
        val b = wall(0.03f, 0.05f, 1.4f, -1.32f)

        val result = GeneralWallTopologyReconciler.reconcile(listOf(a, b))

        assertEquals(2, result.size)
        val closest = result.flatMap { listOf(it.start, it.end) }.sortedBy { distance(it, Vec2(0f, 0f)) }
        assertTrue(distance(closest[0], closest[1]) < 0.002f)
    }

    private fun wall(
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        thickness: Float = 0.18f,
    ) = WallSegment(
        start = Vec2(x0, z0),
        end = Vec2(x1, z1),
        thicknessMeters = thickness,
        confidence = 0.90f,
    )

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }
}
