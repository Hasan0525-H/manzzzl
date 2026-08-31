package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

internal data class StructuralRaster(
    val mask: BooleanArray,
    val preferBlue: Boolean,
)

/** Shared structural-ink classifier used by extraction, verification and explicit user correction. */
internal object StructuralRasterMask {

    fun classify(bitmap: Bitmap): StructuralRaster {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return classify(pixels)
    }

    fun classify(pixels: IntArray): StructuralRaster {
        var blueStructuralCount = 0
        for (color in pixels) {
            if (isBlueStructural(color)) blueStructuralCount++
        }
        val preferBlue = blueStructuralCount >= (pixels.size * BLUE_MODE_MIN_RATIO).toInt()
        val mask = BooleanArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            mask[index] = if (preferBlue) isBlueStructural(color) else isMonochromeStructural(color)
        }
        return StructuralRaster(mask = mask, preferBlue = preferBlue)
    }

    private fun isBlueStructural(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val saturation = maxChannel - minChannel
        return b >= 105 &&
            b > r * 1.18f &&
            b > g * 1.04f &&
            saturation >= 38
    }

    private fun isMonochromeStructural(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (r * 0.2126f) + (g * 0.7152f) + (b * 0.0722f)
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        return luminance <= 112f && chroma <= 58
    }

    private const val BLUE_MODE_MIN_RATIO = 0.0014f
}
