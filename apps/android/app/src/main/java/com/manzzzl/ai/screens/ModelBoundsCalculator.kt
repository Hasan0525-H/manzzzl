package com.manzzzl.ai.screens

import kotlin.math.max

/**
 * Calculates camera framing values from GLB model bounds.
 */
object ModelBoundsCalculator {
    fun calculateCameraDistance(
        width: Float,
        height: Float,
        depth: Float
    ): Float {
        val largest = max(width, max(height, depth))
        return largest * 2.5f
    }

    fun calculateCenter(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        minZ: Float,
        maxZ: Float
    ): FloatArray {
        return floatArrayOf(
            (minX + maxX) / 2f,
            (minY + maxY) / 2f,
            (minZ + maxZ) / 2f
        )
    }
}
