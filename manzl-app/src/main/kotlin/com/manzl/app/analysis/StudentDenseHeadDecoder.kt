package com.manzl.app.analysis

import ai.onnxruntime.OnnxTensor
import kotlin.math.exp

/** Decodes the three student ONNX heads once so wall vectors and semantic evidence share one inference. */
internal object StudentDenseHeadDecoder {

    data class Result(
        val wallMask: BooleanArray,
        val cornerProbability: FloatArray,
        val orientationX: FloatArray,
        val orientationY: FloatArray,
        val semanticComponents: List<StudentSemanticComponentDecoder.Component>,
    )

    fun decode(
        semantic: OnnxTensor,
        corners: OnnxTensor,
        orientation: OnnxTensor,
        side: Int,
        wallLogitMargin: Float,
    ): Result? {
        val plane = side * side
        val semanticValues = semantic.floatBuffer?.let { buffer ->
            if (buffer.remaining() < SEMANTIC_CLASS_COUNT * plane) return null
            FloatArray(SEMANTIC_CLASS_COUNT * plane).also(buffer::get)
        } ?: return null
        val cornerValues = corners.floatBuffer?.let { buffer ->
            if (buffer.remaining() < plane) return null
            FloatArray(plane).also(buffer::get)
        } ?: return null
        val orientationValues = orientation.floatBuffer?.let { buffer ->
            if (buffer.remaining() < 2 * plane) return null
            FloatArray(2 * plane).also(buffer::get)
        } ?: return null

        val wallMask = BooleanArray(plane)
        val classIds = ByteArray(plane)
        val classConfidence = FloatArray(plane)
        for (pixel in 0 until plane) {
            var bestClass = 0
            var best = semanticValues[pixel]
            var runnerUp = Float.NEGATIVE_INFINITY
            for (clazz in 1 until SEMANTIC_CLASS_COUNT) {
                val value = semanticValues[clazz * plane + pixel]
                if (value > best) {
                    runnerUp = best
                    best = value
                    bestClass = clazz
                } else if (value > runnerUp) {
                    runnerUp = value
                }
            }
            val margin = if (runnerUp.isFinite()) best - runnerUp else 0f
            classIds[pixel] = bestClass.toByte()
            classConfidence[pixel] = sigmoid(margin)
            wallMask[pixel] = bestClass == WALL_CLASS_ID && margin >= wallLogitMargin
        }

        // Decode every semantic class that can improve reconstruction without inventing geometry.
        // Door/window components later classify measured gaps, stairs are dimension-gated, and
        // courtyard/shaft components can only label an already reconstructed closed room polygon.
        // Columns are intentionally decoded too so the source-space observation is retained for the
        // next structural-primitive pass; until that pass verifies raster support they are not added
        // to canonical geometry.
        val semanticComponents = StudentSemanticComponentDecoder.decode(
            classIds = classIds,
            classConfidence = classConfidence,
            side = side,
            targetClassIds = setOf(
                StudentSemanticComponentDecoder.DOOR_CLASS_ID,
                StudentSemanticComponentDecoder.WINDOW_CLASS_ID,
                StudentSemanticComponentDecoder.STAIR_CLASS_ID,
                StudentSemanticComponentDecoder.COLUMN_CLASS_ID,
                StudentSemanticComponentDecoder.COURTYARD_CLASS_ID,
                StudentSemanticComponentDecoder.SHAFT_CLASS_ID,
            ),
            maxComponents = MAX_SEMANTIC_COMPONENTS,
        )

        return Result(
            wallMask = wallMask,
            cornerProbability = FloatArray(plane) { index -> sigmoid(cornerValues[index]) },
            orientationX = orientationValues.copyOfRange(0, plane),
            orientationY = orientationValues.copyOfRange(plane, plane * 2),
            semanticComponents = semanticComponents,
        )
    }

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    private const val SEMANTIC_CLASS_COUNT = 9
    private const val WALL_CLASS_ID = 1
    private const val MAX_SEMANTIC_COMPONENTS = 128
}
