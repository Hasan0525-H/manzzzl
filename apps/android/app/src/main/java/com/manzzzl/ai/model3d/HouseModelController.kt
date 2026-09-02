package com.manzzzl.ai.model3d

import io.github.sceneview.node.ModelNode

/**
 * Manages house model lifecycle and bounds calculation.
 * Provides safe access to model node and calculated bounds.
 */
class HouseModelController {
    private var modelNode: ModelNode? = null

    fun attach(node: ModelNode?) {
        modelNode = node
    }

    fun getNode(): ModelNode? = modelNode

    fun getBounds(): ModelBoundsCalculator.BoundsResult? {
        return try {
            modelNode?.let { node ->
                if (node.parent != null) {
                    ModelBoundsCalculator.calculate(node)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
