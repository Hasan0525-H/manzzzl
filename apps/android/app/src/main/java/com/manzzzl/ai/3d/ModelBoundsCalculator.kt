package com.manzzzl.ai.model3d

import io.github.sceneview.node.ModelNode
import com.google.android.filament.Box
import com.google.android.filament.math.Float3

object ModelBoundsCalculator {
    data class BoundsResult(
        val center: Float3,
        val radius: Float,
        val cameraDistance: Float
    )

    fun calculate(modelNode: ModelNode): BoundsResult {
        val extents = modelNode.extents
        val size = extents.size
        val center = extents.center

        val radius = maxOf(size.x, size.y, size.z) * 0.5f
        val cameraDistance = radius * 2.8f

        return BoundsResult(
            center = center,
            radius = radius,
            cameraDistance = cameraDistance
        )
    }
}
