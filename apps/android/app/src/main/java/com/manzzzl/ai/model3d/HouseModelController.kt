package com.manzzzl.ai.model3d

import io.github.sceneview.node.ModelNode

class HouseModelController {
    private var modelNode: ModelNode? = null

    fun attach(node: ModelNode) {
        modelNode = node
    }

    fun getNode(): ModelNode? = modelNode

    fun getBounds(): ModelBoundsCalculator.BoundsResult? {
        return modelNode?.let {
            ModelBoundsCalculator.calculate(it)
        }
    }
}
