package com.manzzzl.ai.model3d

import io.github.sceneview.cameranode.CameraNode

/**
 * Central camera logic for 3D house viewing.
 * Handles automatic framing based on model bounds.
 * - Calculates optimal camera distance to fit model in view
 * - Provides reset capability to return to initial framing
 * - Used by SceneModelView for auto-framing and reset button
 */
class CameraController {

    private var lastBounds: ModelBoundsCalculator.BoundsResult? = null

    /**
     * Calculate camera position and target based on model bounds.
     * @param bounds The bounding box of the model
     * @return CameraFrame with position and target coordinates
     */
    fun frame(
        bounds: ModelBoundsCalculator.BoundsResult
    ): CameraFrame {
        lastBounds = bounds

        return CameraFrame(
            position = calculateCameraPosition(bounds),
            target = getLookAtTarget(bounds)
        )
    }

    /**
     * Reset camera to the last calculated framing.
     * @return CameraFrame for the last known bounds, or null if no bounds were set
     */
    fun reset(): CameraFrame? {
        return lastBounds?.let { frame(it) }
    }

    /**
     * Calculate optimal camera position to view the entire model.
     * Position is placed along Z-axis away from model center.
     */
    fun calculateCameraPosition(
        bounds: ModelBoundsCalculator.BoundsResult
    ): FloatArray {
        return floatArrayOf(
            bounds.centerX,
            bounds.centerY,
            bounds.centerZ + bounds.cameraDistance
        )
    }

    /**
     * Calculate look-at target (center of model).
     */
    fun getLookAtTarget(
        bounds: ModelBoundsCalculator.BoundsResult
    ): FloatArray {
        return floatArrayOf(
            bounds.centerX,
            bounds.centerY,
            bounds.centerZ
        )
    }

    /**
     * Represents camera position and target for framing.
     */
    data class CameraFrame(
        val position: FloatArray,
        val target: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CameraFrame) return false
            return position.contentEquals(other.position) &&
                    target.contentEquals(other.target)
        }

        override fun hashCode(): Int {
            return position.contentHashCode() * 31 + target.contentHashCode()
        }
    }
}
