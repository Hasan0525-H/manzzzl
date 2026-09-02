package com.manzzzl.ai.engine

/**
 * Controls the 3D viewer state.
 *
 * This layer keeps the UI independent from the rendering engine.
 * A real renderer can be connected later without changing the app flow.
 */
class ThreeDViewerController {
    private var rotationX: Float = 0f
    private var rotationY: Float = 0f
    private var zoom: Float = 1f

    fun rotate(x: Float, y: Float) {
        rotationX += x
        rotationY += y
    }

    fun zoom(value: Float) {
        zoom = value.coerceIn(0.5f, 3f)
    }

    fun reset() {
        rotationX = 0f
        rotationY = 0f
        zoom = 1f
    }

    fun state(): ViewerState = ViewerState(
        rotationX = rotationX,
        rotationY = rotationY,
        zoom = zoom
    )
}

data class ViewerState(
    val rotationX: Float,
    val rotationY: Float,
    val zoom: Float
)
