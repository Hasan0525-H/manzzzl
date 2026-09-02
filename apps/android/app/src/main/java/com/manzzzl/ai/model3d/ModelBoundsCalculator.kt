package com.manzzzl.ai.model3d

import io.github.sceneview.node.ModelNode

/**
 * Calculates model framing data.
 * SceneView bounds extraction is connected here so camera logic stays isolated.
 */
object ModelBoundsCalculator {

    data class BoundsResult(
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val cameraDistance: Float
    )

    fun calculate(modelNode: ModelNode): BoundsResult {
        // Placeholder until the exact SceneView 2.2.1 bounds API is wired.
        // Keeps camera/controller architecture ready.
        return BoundsResult(
            centerX = 0f,
            centerY = 0f,
            centerZ = 0f,
            cameraDistance = 5f
        )
    }
}
