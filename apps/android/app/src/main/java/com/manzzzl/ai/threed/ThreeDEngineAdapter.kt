package com.manzzzl.ai.threed

/**
 * Abstraction layer for the 3D renderer.
 *
 * Keeps the app independent from a specific rendering engine so we can use
 * a free/open-source solution during development and replace it later if needed.
 */
interface ThreeDEngineAdapter {
    fun loadModel(modelPath: String)
    fun rotate(deltaX: Float, deltaY: Float)
    fun zoom(scale: Float)
    fun resetCamera()
}
