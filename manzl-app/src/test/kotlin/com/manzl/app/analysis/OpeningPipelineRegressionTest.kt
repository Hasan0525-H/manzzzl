package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage across the real deterministic opening stages, not isolated helpers. */
class OpeningPipelineRegressionTest {

    @Test
    fun `narrow measured window is not permanently mislabeled as baseline door`() {
        val width = 1000
        val height = 800
        val pixels = IntArray(width * height) { WHITE }
        // 1.2 m measured gap centred at zero. Two long bands represent the window symbol.
        drawHorizontal(pixels, width, y = 390, fromX = 455, toX = 545)
        drawHorizontal(pixels, width, y = 410, fromX = 455, toX = 545)

        val measured = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = listOf(
                WallSegment(Vec2(-4f, 0f), Vec2(-0.6f, 0f), confidence = 0.96f),
                WallSegment(Vec2(0.6f, 0f), Vec2(4f, 0f), confidence = 0.95f),
            ),
            analysisConfidence = 0.95f,
            sourceWidthPx = width,
            sourceHeightPx = height,
        )
        val baseline = measured.copy(doors = DoorInferenceEngine.infer(measured))
        assertEquals(1, baseline.doors.size)

        val windowEvidence = WindowSymbolEvidenceProvider().detectFromPixels(
            pixels = pixels,
            width = width,
            height = height,
            structuralPlan = baseline,
        )
        assertEquals(1, windowEvidence.size)
        assertEquals(SemanticKind.WINDOW, windowEvidence.single().kind)

        val fused = GeometryEvidenceFusion.fuse(baseline, windowEvidence)
        val reconciled = OpeningSemanticReconciler.reconcile(fused)

        assertTrue("geometry-only door should yield to the stronger measured window symbol", reconciled.doors.isEmpty())
        assertEquals(1, reconciled.windows.size)
        assertEquals(1.2f, reconciled.windows.single().widthMeters, 0.03f)
        assertEquals(0f, reconciled.windows.single().rotationDegrees, 0.01f)
    }

    private fun drawHorizontal(
        pixels: IntArray,
        width: Int,
        y: Int,
        fromX: Int,
        toX: Int,
    ) {
        for (x in fromX..toX) pixels[y * width + x] = BLACK
    }

    companion object {
        private const val WHITE: Int = -1
        private const val BLACK: Int = -16777216
    }
}
