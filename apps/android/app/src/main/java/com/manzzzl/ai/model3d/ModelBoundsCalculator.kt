package com.manzzzl.ai.model3d

import io.github.sceneview.node.ModelNode
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Calculates camera framing data from the real GLB bounds exposed by SceneView ModelNode.
 */
object ModelBoundsCalculator {

    data class BoundsResult(
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val sizeX: Float,
        val sizeY: Float,
        val sizeZ: Float,
        val radius: Float,
        val cameraDistance: Float
    )

    fun calculate(modelNode: ModelNode): BoundsResult {
        val center = modelNode.center
        val size = modelNode.size

        val sizeX = size.x
        val sizeY = size.y
        val sizeZ = size.z
        val largestDimension = max(sizeX, max(sizeY, sizeZ)).coerceAtLeast(0.001f)
        val radius = 0.5f * sqrt(
            sizeX * sizeX +
                sizeY * sizeY +
                sizeZ * sizeZ
        )

        // Conservative architectural framing distance. Keeps the full model in view while
        // leaving a small visual margin for orbit and pinch-zoom interaction.
        val cameraDistance = max(radius * 2.4f, largestDimension * 1.35f)

        return BoundsResult(
            centerX = center.x,
            centerY = center.y,
            centerZ = center.z,
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            radius = radius,
            cameraDistance = cameraDistance
        )
    }
}
