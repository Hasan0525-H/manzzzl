package com.manzl.app.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairPatternDetectorTest {

    @Test
    fun `detects a regular horizontal tread band`() {
        val width = 220
        val height = 180
        val mask = BooleanArray(width * height)
        for (y in 48..112 step 8) {
            for (x in 64..142) {
                mask[y * width + x] = true
            }
        }

        val candidates = StairPatternDetector.detect(mask, width, height)

        assertFalse(candidates.isEmpty())
        val best = candidates.maxBy { it.confidence }
        assertTrue(best.treadsHorizontal)
        assertTrue(best.confidence >= 0.64f)
        assertTrue(best.treadLengthPx in 70f..90f)
        assertTrue(best.bandLengthPx >= 55f)
    }

    @Test
    fun `rejects too few parallel strokes`() {
        val width = 200
        val height = 160
        val mask = BooleanArray(width * height)
        for (y in listOf(50, 62, 74)) {
            for (x in 70..130) mask[y * width + x] = true
        }

        assertTrue(StairPatternDetector.detect(mask, width, height).isEmpty())
    }
}
