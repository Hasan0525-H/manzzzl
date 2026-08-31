package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSymbolEvidenceProviderTest {

    @Test
    fun `two sustained parallel bands inside wall gap produce window evidence`() {
        val width = 1000
        val height = 800
        val pixels = IntArray(width * height) { WHITE }
        drawHorizontal(pixels, width, y = 390, fromX = 430, toX = 570, color = BLACK)
        drawHorizontal(pixels, width, y = 410, fromX = 430, toX = 570, color = BLACK)

        val plan = horizontalGapPlan()
        val evidence = WindowSymbolEvidenceProvider().detectFromPixels(pixels, width, height, plan)

        assertEquals(1, evidence.size)
        assertEquals(SemanticKind.WINDOW, evidence.single().kind)
        assertEquals(0f, evidence.single().center.x, 0.001f)
        assertEquals(0f, evidence.single().center.z, 0.001f)
        assertEquals(2f, evidence.single().widthMeters ?: 0f, 0.001f)
        assertTrue(evidence.single().confidence >= 0.66f)
    }

    @Test
    fun `single ink band is insufficient and fails closed`() {
        val width = 1000
        val height = 800
        val pixels = IntArray(width * height) { WHITE }
        drawHorizontal(pixels, width, y = 400, fromX = 430, toX = 570, color = BLACK)

        val evidence = WindowSymbolEvidenceProvider().detectFromPixels(
            pixels,
            width,
            height,
            horizontalGapPlan(),
        )

        assertTrue(evidence.isEmpty())
    }

    @Test
    fun `known door occupying the same gap excludes window inference`() {
        val width = 1000
        val height = 800
        val pixels = IntArray(width * height) { WHITE }
        drawHorizontal(pixels, width, y = 390, fromX = 430, toX = 570, color = BLACK)
        drawHorizontal(pixels, width, y = 410, fromX = 430, toX = 570, color = BLACK)
        val plan = horizontalGapPlan().copy(
            doors = listOf(
                DoorOpening(
                    center = Vec2(0f, 0f),
                    widthMeters = 1f,
                    rotationDegrees = 0f,
                    confidence = 0.9f,
                )
            )
        )

        val evidence = WindowSymbolEvidenceProvider().detectFromPixels(pixels, width, height, plan)

        assertTrue(evidence.isEmpty())
    }

    @Test
    fun `vertical double band is detected with ninety degree axis`() {
        val width = 1000
        val height = 800
        val pixels = IntArray(width * height) { WHITE }
        drawVertical(pixels, width, x = 490, fromY = 330, toY = 470, color = BLACK)
        drawVertical(pixels, width, x = 510, fromY = 330, toY = 470, color = BLACK)
        val plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = listOf(
                WallSegment(Vec2(0f, -4f), Vec2(0f, -1f)),
                WallSegment(Vec2(0f, 1f), Vec2(0f, 4f)),
            ),
            analysisConfidence = 0.9f,
            sourceWidthPx = width,
            sourceHeightPx = height,
        )

        val evidence = WindowSymbolEvidenceProvider().detectFromPixels(pixels, width, height, plan)

        assertEquals(1, evidence.size)
        assertEquals(90f, evidence.single().rotationDegrees ?: -1f, 0.001f)
    }

    private fun horizontalGapPlan() = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = listOf(
            WallSegment(Vec2(-4f, 0f), Vec2(-1f, 0f)),
            WallSegment(Vec2(1f, 0f), Vec2(4f, 0f)),
        ),
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 800,
    )

    private fun drawHorizontal(
        pixels: IntArray,
        width: Int,
        y: Int,
        fromX: Int,
        toX: Int,
        color: Int,
    ) {
        for (x in fromX..toX) pixels[y * width + x] = color
    }

    private fun drawVertical(
        pixels: IntArray,
        width: Int,
        x: Int,
        fromY: Int,
        toY: Int,
        color: Int,
    ) {
        for (y in fromY..toY) pixels[y * width + x] = color
    }

    companion object {
        private const val WHITE: Int = -1
        private const val BLACK: Int = -16777216
    }
}
