package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class TinySemanticPatchModelTest {

    @Test
    fun `canonical window patch is classified as window`() {
        val patch = blank()
        for (x in 2..13) {
            patch[index(x, 7)] = 1f
            patch[index(x, 9)] = 1f
        }

        val prediction = TinySemanticPatchModel.predict(patch)
        assertEquals(TinySemanticPatchModel.PatchClass.WINDOW, prediction.label)
        assertTrue(prediction.confidence > 0.90f)
        assertTrue(prediction.margin > 0.70f)
    }

    @Test
    fun `canonical repeated tread patch is classified as stair`() {
        val patch = blank()
        for (y in listOf(3, 5, 7, 9, 11, 13)) {
            for (x in 3..12) patch[index(x, y)] = 1f
        }

        val prediction = TinySemanticPatchModel.predict(patch)
        assertEquals(TinySemanticPatchModel.PatchClass.STAIR, prediction.label)
        assertTrue(prediction.confidence > 0.95f)
    }

    @Test
    fun `quarter arc plus leaf is classified as door`() {
        val patch = blank()
        val hingeX = 3
        val hingeY = 12
        val radius = 8
        for (x in hingeX..hingeX + radius) patch[index(x, hingeY)] = 1f
        for (degrees in 270..360 step 2) {
            val radians = Math.toRadians(degrees.toDouble())
            val x = (hingeX + cos(radians) * radius).roundToInt()
            val y = (hingeY + sin(radians) * radius).roundToInt()
            if (x in 0..15 && y in 0..15) patch[index(x, y)] = 1f
        }
        for (y in hingeY - 2..hingeY + 2) if (y in 0..15) patch[index(hingeX, y)] = 1f

        val prediction = TinySemanticPatchModel.predict(patch)
        assertEquals(TinySemanticPatchModel.PatchClass.DOOR, prediction.label)
        assertTrue(prediction.confidence > 0.75f)
    }

    @Test
    fun `empty patch remains other`() {
        val prediction = TinySemanticPatchModel.predict(blank())
        assertEquals(TinySemanticPatchModel.PatchClass.OTHER, prediction.label)
        assertTrue(prediction.confidence > 0.90f)
    }

    private fun blank() = FloatArray(16 * 16)
    private fun index(x: Int, y: Int) = y * 16 + x
}
