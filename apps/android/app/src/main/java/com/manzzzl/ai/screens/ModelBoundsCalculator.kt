package com.manzzzl.ai.screens

import kotlin.math.max

/**
 * Calculates a safe camera distance from model dimensions.
 * The actual bounds are supplied after the GLB instance is created.
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
}
