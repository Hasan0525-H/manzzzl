package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

internal data class StructuralRaster(
    val mask: BooleanArray,
    val preferBlue: Boolean,
)

/**
 * Shared structural-ink classifier used by extraction, verification and explicit user correction.
 *
 * A tiny amount of blue annotation must never switch a normal black architectural drawing into
 * "blue mode". Both color hypotheses are therefore built first and compared by long directional
 * line evidence. Extremely dense dark-background masks are penalized so blueprint backgrounds do not
 * masquerade as walls. The exact chosen mask is reused by extraction and independent verification.
 */
internal object StructuralRasterMask {

    fun classify(bitmap: Bitmap): StructuralRaster {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return classify(pixels, bitmap.width, bitmap.height)
    }

    /** Backwards-compatible fallback for callers/tests that do not carry raster dimensions. */
    fun classify(pixels: IntArray): StructuralRaster {
        val blue = BooleanArray(pixels.size)
        val mono = BooleanArray(pixels.size)
        var blueCount = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val isBlue = isBlueStructural(color)
            blue[index] = isBlue
            mono[index] = isMonochromeStructural(color)
            if (isBlue) blueCount++
        }
        val preferBlue = blueCount >= (pixels.size * BLUE_MODE_MIN_RATIO).toInt()
        return StructuralRaster(mask = if (preferBlue) blue else mono, preferBlue = preferBlue)
    }

    fun classify(pixels: IntArray, width: Int, height: Int): StructuralRaster {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return classify(pixels)
        val blue = BooleanArray(pixels.size)
        val mono = BooleanArray(pixels.size)
        var blueCount = 0
        var monoCount = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val isBlue = isBlueStructural(color)
            val isMono = isMonochromeStructural(color)
            blue[index] = isBlue
            mono[index] = isMono
            if (isBlue) blueCount++
            if (isMono) monoCount++
        }

        val blueEligible = blueCount >= max(MIN_MODE_PIXELS, (pixels.size * BLUE_MODE_MIN_RATIO).toInt())
        if (!blueEligible) return StructuralRaster(mask = mono, preferBlue = false)

        val blueScore = directionalLineScore(blue, width, height, blueCount)
        val monoScore = directionalLineScore(mono, width, height, monoCount)
        val preferBlue = blueScore >= MIN_BLUE_LINE_SCORE &&
            blueScore >= monoScore * BLUE_SCORE_ADVANTAGE
        return StructuralRaster(mask = if (preferBlue) blue else mono, preferBlue = preferBlue)
    }

    private fun directionalLineScore(
        mask: BooleanArray,
        width: Int,
        height: Int,
        inkCount: Int,
    ): Float {
        if (inkCount <= 0) return 0f
        val density = inkCount / mask.size.toFloat()
        if (density >= MAX_PLAUSIBLE_INK_DENSITY) return 0f

        var sampledInk = 0
        var lineSupported = 0
        val directions = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1),
            intArrayOf(2, 1),
            intArrayOf(1, 2),
            intArrayOf(2, -1),
            intArrayOf(1, -2),
        )
        for (y in LINE_RADIUS until height - LINE_RADIUS step SCORE_SAMPLE_STEP) {
            val row = y * width
            for (x in LINE_RADIUS until width - LINE_RADIUS step SCORE_SAMPLE_STEP) {
                if (!mask[row + x]) continue
                sampledInk++
                var supported = false
                for (direction in directions) {
                    var hits = 0
                    var valid = 0
                    for (step in -LINE_RADIUS..LINE_RADIUS) {
                        val sx = x + direction[0] * step
                        val sy = y + direction[1] * step
                        if (sx !in 0 until width || sy !in 0 until height) continue
                        valid++
                        if (mask[sy * width + sx]) hits++
                    }
                    if (valid >= MIN_DIRECTION_SAMPLES &&
                        hits >= MIN_DIRECTION_HITS &&
                        hits / valid.toFloat() >= MIN_DIRECTION_RATIO
                    ) {
                        supported = true
                        break
                    }
                }
                if (supported) lineSupported++
            }
        }
        if (sampledInk == 0) return 0f
        val supportRatio = lineSupported / sampledInk.toFloat()
        val densityPenalty = when {
            density <= NORMAL_INK_DENSITY -> 1f
            else -> ((MAX_PLAUSIBLE_INK_DENSITY - density) /
                (MAX_PLAUSIBLE_INK_DENSITY - NORMAL_INK_DENSITY)).coerceIn(0f, 1f)
        }
        // Absolute supported-pixel count prevents one long colored annotation line from beating an
        // entire monochrome wall network just because the annotation's support ratio is near 1.0.
        return lineSupported * supportRatio * densityPenalty
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
    private const val MIN_MODE_PIXELS = 20
    private const val SCORE_SAMPLE_STEP = 2
    private const val LINE_RADIUS = 5
    private const val MIN_DIRECTION_SAMPLES = 7
    private const val MIN_DIRECTION_HITS = 6
    private const val MIN_DIRECTION_RATIO = 0.66f
    private const val NORMAL_INK_DENSITY = 0.16f
    private const val MAX_PLAUSIBLE_INK_DENSITY = 0.42f
    private const val MIN_BLUE_LINE_SCORE = 4f
    private const val BLUE_SCORE_ADVANTAGE = 1.08f
}
