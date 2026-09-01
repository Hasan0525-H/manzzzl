package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class WallGeometryV2Test {

    @Test
    fun `measures double line wall face thickness instead of fixed default`() {
        val width = 220
        val height = 220
        val mask = BooleanArray(width * height)
        for (x in 28..192) {
            for (y in 99..101) mask[y * width + x] = true
            for (y in 118..120) mask[y * width + x] = true
        }
        val wall = WallSegment(
            start = Vec2(-0.82f, 0f),
            end = Vec2(0.82f, 0f),
            thicknessMeters = 0.18f,
        )

        val refined = WallGeometryV2.refine(
            structuralMask = mask,
            imageWidth = width,
            imageHeight = height,
            bounds = PixelContentBounds.full(width, height),
            pxToMeter = 0.01f,
            baseWalls = listOf(wall),
        )

        val thickness = refined.first().thicknessMeters
        assertTrue("measured thickness=$thickness", thickness in 0.17f..0.25f)
    }

    @Test
    fun `recovers long arbitrary angle wall from raster evidence`() {
        val width = 240
        val height = 240
        val mask = BooleanArray(width * height)
        drawThickSegment(mask, width, height, 38f, 201f, 202f, 39f, radius = 3.5f)

        val refined = WallGeometryV2.refine(
            structuralMask = mask,
            imageWidth = width,
            imageHeight = height,
            bounds = PixelContentBounds.full(width, height),
            pxToMeter = 0.02f,
            baseWalls = emptyList(),
        )

        val diagonal = refined.maxByOrNull { wallLength(it) }
        assertTrue("expected at least one diagonal wall", diagonal != null)
        val wall = requireNotNull(diagonal)
        val angle = Math.toDegrees(
            atan2(
                (wall.end.z - wall.start.z).toDouble(),
                (wall.end.x - wall.start.x).toDouble(),
            )
        ).toFloat()
        val normalized = ((angle % 180f) + 180f) % 180f
        val distanceFromAxis = minOf(abs(normalized), abs(normalized - 90f), abs(normalized - 180f))
        assertTrue("angle=$normalized", distanceFromAxis > 12f)
        assertTrue("length=${wallLength(wall)}", wallLength(wall) > 2.2f)
    }

    private fun drawThickSegment(
        mask: BooleanArray,
        width: Int,
        height: Int,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radius: Float,
    ) {
        val radiusSq = radius * radius
        for (y in 0 until height) {
            for (x in 0 until width) {
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

    private fun wallLength(wall: WallSegment): Float {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        return sqrt(dx * dx + dz * dz)
    }
}
