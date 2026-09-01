package com.manzl.app.analysis

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extracts connected semantic objects from the student's dense class map without granting them any
 * geometry authority. Door/window components are later matched to an already measured wall gap;
 * stairs are bounded by deterministic dimension checks. Unknown/weak blobs are simply discarded.
 */
internal object StudentSemanticComponentDecoder {

    data class Component(
        val classId: Int,
        val centerX: Float,
        val centerY: Float,
        val majorSpanPx: Float,
        val minorSpanPx: Float,
        val rotationDegrees: Float,
        val confidence: Float,
        val pixelCount: Int,
        val touchesModelEdge: Boolean,
    )

    fun decode(
        classIds: ByteArray,
        classConfidence: FloatArray,
        side: Int,
        targetClassIds: Set<Int>,
        maxComponents: Int = DEFAULT_MAX_COMPONENTS,
    ): List<Component> {
        val plane = side * side
        if (
            side < 8 || classIds.size != plane || classConfidence.size != plane ||
            targetClassIds.isEmpty() || maxComponents <= 0
        ) return emptyList()

        val visited = BooleanArray(plane)
        val queue = IntArray(plane)
        val output = ArrayList<Component>()

        for (seed in 0 until plane) {
            if (visited[seed]) continue
            val classId = classIds[seed].toInt() and 0xff
            if (classId !in targetClassIds) {
                visited[seed] = true
                continue
            }

            var head = 0
            var tail = 0
            queue[tail++] = seed
            visited[seed] = true
            val pixels = ArrayList<Int>()
            var confidenceSum = 0f
            var touchesEdge = false

            while (head < tail) {
                val index = queue[head++]
                pixels += index
                confidenceSum += classConfidence[index].coerceIn(0f, 1f)
                val x = index % side
                val y = index / side
                if (x <= MODEL_EDGE_MARGIN || y <= MODEL_EDGE_MARGIN ||
                    x >= side - 1 - MODEL_EDGE_MARGIN || y >= side - 1 - MODEL_EDGE_MARGIN
                ) touchesEdge = true

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until side) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until side) continue
                        val neighbor = ny * side + nx
                        if (visited[neighbor]) continue
                        val neighborClass = classIds[neighbor].toInt() and 0xff
                        if (neighborClass != classId) continue
                        visited[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }

            val minimumPixels = minimumPixelsForClass(classId)
            if (pixels.size < minimumPixels) continue
            val meanConfidence = confidenceSum / pixels.size.toFloat()
            if (meanConfidence < MIN_COMPONENT_CONFIDENCE) continue
            componentFromPixels(
                classId = classId,
                pixels = pixels,
                side = side,
                confidence = meanConfidence,
                touchesEdge = touchesEdge,
            )?.let(output::add)
        }

        return output
            .sortedWith(
                compareByDescending<Component> { it.confidence }
                    .thenByDescending { it.pixelCount }
            )
            .take(maxComponents)
    }

    private fun componentFromPixels(
        classId: Int,
        pixels: List<Int>,
        side: Int,
        confidence: Float,
        touchesEdge: Boolean,
    ): Component? {
        if (pixels.isEmpty()) return null
        var meanX = 0.0
        var meanY = 0.0
        pixels.forEach { index ->
            meanX += (index % side).toDouble()
            meanY += (index / side).toDouble()
        }
        meanX /= pixels.size
        meanY /= pixels.size

        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        pixels.forEach { index ->
            val dx = (index % side) - meanX
            val dy = (index / side) - meanY
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        xx /= pixels.size
        yy /= pixels.size
        xy /= pixels.size
        val angle = 0.5 * atan2(2.0 * xy, xx - yy)
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val nx = -uy
        val ny = ux

        var majorMin = Float.POSITIVE_INFINITY
        var majorMax = Float.NEGATIVE_INFINITY
        var minorMin = Float.POSITIVE_INFINITY
        var minorMax = Float.NEGATIVE_INFINITY
        pixels.forEach { index ->
            val x = (index % side).toFloat() - meanX.toFloat()
            val y = (index / side).toFloat() - meanY.toFloat()
            val major = x * ux + y * uy
            val minor = x * nx + y * ny
            majorMin = min(majorMin, major)
            majorMax = max(majorMax, major)
            minorMin = min(minorMin, minor)
            minorMax = max(minorMax, minor)
        }
        val majorSpan = majorMax - majorMin + 1f
        val minorSpan = minorMax - minorMin + 1f
        if (!majorSpan.isFinite() || !minorSpan.isFinite()) return null

        return Component(
            classId = classId,
            centerX = meanX.toFloat(),
            centerY = meanY.toFloat(),
            majorSpanPx = max(majorSpan, minorSpan),
            minorSpanPx = min(majorSpan, minorSpan),
            rotationDegrees = normalizeHalfTurnDegrees(Math.toDegrees(angle).toFloat()),
            confidence = confidence.coerceIn(0f, 1f),
            pixelCount = pixels.size,
            touchesModelEdge = touchesEdge,
        )
    }

    private fun minimumPixelsForClass(classId: Int): Int = when (classId) {
        DOOR_CLASS_ID, WINDOW_CLASS_ID -> 6
        STAIR_CLASS_ID -> 12
        else -> 10
    }

    private fun normalizeHalfTurnDegrees(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    const val DOOR_CLASS_ID = 2
    const val WINDOW_CLASS_ID = 3
    const val STAIR_CLASS_ID = 4
    const val COLUMN_CLASS_ID = 5
    const val COURTYARD_CLASS_ID = 7
    const val SHAFT_CLASS_ID = 8

    private const val MODEL_EDGE_MARGIN = 2
    private const val MIN_COMPONENT_CONFIDENCE = 0.54f
    private const val DEFAULT_MAX_COMPONENTS = 96
}
