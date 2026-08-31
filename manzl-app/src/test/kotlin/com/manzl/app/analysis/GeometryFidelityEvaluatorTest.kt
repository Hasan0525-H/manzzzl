package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class GeometryFidelityEvaluatorTest {

    @Test
    fun `matching wall faces pass independent raster fidelity gate`() {
        val width = 220
        val height = 220
        val walls = rectangleWalls()
        val mask = BooleanArray(width * height)
        walls.forEach { drawWall(mask, width, height, it, planSpanMeters = 10f) }
        val plan = plan(walls)

        val report = GeometryFidelityEvaluator.evaluate(mask, width, height, plan)

        assertEquals(GeometryFidelityStatus.PASS, report.status)
        assertTrue(report.score > 0.78f)
        assertTrue(report.wallCoverage > 0.70f)
        assertTrue(report.wallPrecision > 0.78f)
        assertTrue(report.endpointSupport > 0.90f)
        assertTrue(report.issues.size <= 1)
    }

    @Test
    fun `clean looking but displaced reconstruction scores much worse and localizes disagreement`() {
        val width = 220
        val height = 220
        val sourceWalls = rectangleWalls()
        val mask = BooleanArray(width * height)
        sourceWalls.forEach { drawWall(mask, width, height, it, planSpanMeters = 10f) }

        val shifted = sourceWalls.map { wall ->
            wall.copy(
                start = wall.start.copy(x = wall.start.x + 1.35f),
                end = wall.end.copy(x = wall.end.x + 1.35f),
            )
        }
        val good = GeometryFidelityEvaluator.evaluate(mask, width, height, plan(sourceWalls))
        val bad = GeometryFidelityEvaluator.evaluate(mask, width, height, plan(shifted))

        assertTrue(bad.score < good.score - 0.22f)
        assertTrue(bad.status != GeometryFidelityStatus.PASS)
        assertTrue(bad.issues.isNotEmpty())
        assertTrue(bad.issues.any { it.kind == GeometryFidelityIssueKind.MISSING_SOURCE })
        assertTrue(bad.issues.any { it.kind == GeometryFidelityIssueKind.EXTRA_GEOMETRY })
        assertTrue(bad.issues.all {
            it.leftFraction in 0f..1f &&
                it.topFraction in 0f..1f &&
                it.rightFraction in 0f..1f &&
                it.bottomFraction in 0f..1f &&
                it.rightFraction > it.leftFraction &&
                it.bottomFraction > it.topFraction
        })
    }

    private fun rectangleWalls(): List<WallSegment> = listOf(
        WallSegment(Vec2(-4f, -4f), Vec2(4f, -4f), thicknessMeters = 0.20f),
        WallSegment(Vec2(4f, -4f), Vec2(4f, 4f), thicknessMeters = 0.20f),
        WallSegment(Vec2(4f, 4f), Vec2(-4f, 4f), thicknessMeters = 0.20f),
        WallSegment(Vec2(-4f, 4f), Vec2(-4f, -4f), thicknessMeters = 0.20f),
        WallSegment(Vec2(-4f, 0f), Vec2(4f, 0f), thicknessMeters = 0.16f),
    )

    private fun plan(walls: List<WallSegment>) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 10f,
        walls = walls,
        analysisConfidence = 0.9f,
        sourceWidthPx = 220,
        sourceHeightPx = 220,
    )

    private fun drawWall(
        mask: BooleanArray,
        width: Int,
        height: Int,
        wall: WallSegment,
        planSpanMeters: Float,
    ) {
        val ppmX = width / planSpanMeters
        val ppmY = height / planSpanMeters
        val ax = width * 0.5f + wall.start.x * ppmX
        val ay = height * 0.5f + wall.start.z * ppmY
        val bx = width * 0.5f + wall.end.x * ppmX
        val by = height * 0.5f + wall.end.z * ppmY
        val radius = wall.thicknessMeters * (ppmX + ppmY) * 0.25f
        val minX = floor(minOf(ax, bx) - radius - 1f).toInt().coerceAtLeast(0)
        val maxX = ceil(maxOf(ax, bx) + radius + 1f).toInt().coerceAtMost(width - 1)
        val minY = floor(minOf(ay, by) - radius - 1f).toInt().coerceAtLeast(0)
        val maxY = ceil(maxOf(ay, by) + radius + 1f).toInt().coerceAtMost(height - 1)
        val radiusSq = radius * radius
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (distanceSquared(x + 0.5f, y + 0.5f, ax, ay, bx, by) <= radiusSq) {
                    mask[y * width + x] = true
                }
            }
        }
    }

    private fun distanceSquared(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val lenSq = vx * vx + vy * vy
        val t = if (lenSq <= 0.000001f) 0f else (((px - ax) * vx + (py - ay) * vy) / lenSq).coerceIn(0f, 1f)
        val qx = ax + vx * t
        val qy = ay + vy * t
        val dx = px - qx
        val dy = py - qy
        return dx * dx + dy * dy
    }
}
