package com.manzzzl.ai.model3d

import io.github.sceneview.cameranode.CameraNode

/**
 * Controls camera framing independently from SceneModelView.
 * Keeps camera logic separated from model loading.
 */
class CameraController {

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
}
