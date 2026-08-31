package com.manzl.app.analysis

import kotlin.math.max
import kotlin.math.min

/** Builds a SAM box prompt plus positive wall-axis points so long/diagonal walls stay isolated. */
internal object MobileSamWallPrompt {

    data class Prompt(
        val coords: FloatArray,
        val labels: FloatArray,
        val pointCount: Int,
    )

    fun build(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        halfBox: Float,
        imageWidth: Int,
        imageHeight: Int,
        imageScale: Float,
    ): Prompt? {
        if (imageWidth <= 1 || imageHeight <= 1 || imageScale <= 0f || halfBox <= 0f) return null
        val minX = (min(startX, endX) - halfBox).coerceIn(0f, imageWidth - 1f)
        val minY = (min(startY, endY) - halfBox).coerceIn(0f, imageHeight - 1f)
        val maxX = (max(startX, endX) + halfBox).coerceIn(0f, imageWidth - 1f)
        val maxY = (max(startY, endY) + halfBox).coerceIn(0f, imageHeight - 1f)
        if (maxX - minX < MIN_BOX_SPAN_PX || maxY - minY < MIN_BOX_SPAN_PX) return null

        val coords = ArrayList<Float>(10)
        val labels = ArrayList<Float>(5)
        fun point(x: Float, y: Float, label: Float) {
            coords += x.coerceIn(0f, imageWidth - 1f) * imageScale
            coords += y.coerceIn(0f, imageHeight - 1f) * imageScale
            labels += label
        }

        // SAM uses labels 2/3 for opposite box corners.
        point(minX, minY, 2f)
        point(maxX, maxY, 3f)

        // Positive points constrain the selected mask to the measured wall itself. This is especially
        // important for diagonal walls whose axis-aligned box contains a large triangular region and
        // may otherwise include neighboring walls/rooms.
        POSITIVE_T.forEach { t ->
            point(
                x = startX + (endX - startX) * t,
                y = startY + (endY - startY) * t,
                label = 1f,
            )
        }

        return Prompt(
            coords = coords.toFloatArray(),
            labels = labels.toFloatArray(),
            pointCount = labels.size,
        )
    }

    private val POSITIVE_T = floatArrayOf(0.20f, 0.50f, 0.80f)
    private const val MIN_BOX_SPAN_PX = 8f
}
