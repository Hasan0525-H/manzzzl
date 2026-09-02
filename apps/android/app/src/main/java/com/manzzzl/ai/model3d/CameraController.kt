package com.manzzzl.ai.model3d

import io.github.sceneview.cameranode.CameraNode

/**
 * Central camera logic for 3D house viewing.
 * SceneModelView only provides the camera instance and bounds.
 */
class CameraController {

    private var lastBounds: ModelBoundsCalculator.BoundsResult? = null

    fun frame(
        bounds: ModelBoundsCalculator.BoundsResult
    ): CameraFrame {
        lastBounds = bounds

        return CameraFrame(
            position = calculateCameraPosition(bounds),
            target = getLookAtTarget(bounds)
        )
    }

    fun reset(): CameraFrame? {
        return lastBounds?.let { frame(it) }
    }

    fun calculateCameraPosition(
        bounds: ModelBoundsCalculator.BoundsResult
    ): FloatArray {
        return floatArrayOf(
            bounds.centerX,
            bounds.centerY,
            bounds.centerZ + bounds.cameraDistance
        )
    }

    fun getLookAtTarget(
        bounds: ModelBoundsCalculator.BoundsResult
    ): FloatArray {
        return floatArrayOf(
            bounds.centerX,
            bounds.centerY,
            bounds.centerZ
        )
    }

    data class CameraFrame(
        val position: FloatArray,
        val target: FloatArray
    )
}
