package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSamWallPromptTest {

    @Test
    fun `prompt contains box corners and three positive wall points`() {
        val prompt = MobileSamWallPrompt.build(
            startX = 20f,
            startY = 30f,
            endX = 180f,
            endY = 150f,
            halfBox = 12f,
            imageWidth = 220,
            imageHeight = 180,
            imageScale = 0.5f,
        ) ?: error("prompt expected")

        assertEquals(5, prompt.pointCount)
        assertEquals(10, prompt.coords.size)
        assertEquals(listOf(2f, 3f, 1f, 1f, 1f), prompt.labels.toList())
        assertTrue(prompt.coords.all { it.isFinite() && it >= 0f })
    }

    @Test
    fun `prompt clips safely at source bounds`() {
        val prompt = MobileSamWallPrompt.build(
            startX = 1f,
            startY = 2f,
            endX = 98f,
            endY = 97f,
            halfBox = 20f,
            imageWidth = 100,
            imageHeight = 100,
            imageScale = 1f,
        ) ?: error("prompt expected")

        val xs = prompt.coords.toList().chunked(2).map { it[0] }
        val ys = prompt.coords.toList().chunked(2).map { it[1] }
        assertTrue(xs.all { it in 0f..99f })
        assertTrue(ys.all { it in 0f..99f })
    }
}
