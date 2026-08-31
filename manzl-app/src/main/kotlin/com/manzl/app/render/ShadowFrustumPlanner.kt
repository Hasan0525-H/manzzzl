package com.manzl.app.render

import kotlin.math.floor

/**
 * Pure CPU planner for the local directional-light shadow window.
 *
 * The camera-centred square is snapped to whole shadow texels before it is converted to a light
 * projection. That keeps the projected depth map stable while walking slowly and prevents the
 * façade/window surrounds from visibly shimmering on mid-range phone screens.
 */
internal object ShadowFrustumPlanner {

    fun plan(
        focusX: Float,
        focusY: Float,
        focusZ: Float,
        radiusMeters: Float = DEFAULT_RADIUS_METERS,
        mapSize: Int = DEFAULT_MAP_SIZE,
    ): ShadowFrustumPlan {
        require(radiusMeters > 0f) { "Shadow radius must be positive" }
        require(mapSize >= MIN_MAP_SIZE) { "Shadow map is too small" }

        val diameter = radiusMeters * 2f
        val texelWorldSize = diameter / mapSize.toFloat()
        return ShadowFrustumPlan(
            focusX = snap(focusX, texelWorldSize),
            focusY = focusY,
            focusZ = snap(focusZ, texelWorldSize),
            radiusMeters = radiusMeters,
            texelWorldSize = texelWorldSize,
        )
    }

    private fun snap(value: Float, step: Float): Float =
        floor(value / step + 0.5f) * step

    const val DEFAULT_MAP_SIZE = 1024
    const val DEFAULT_RADIUS_METERS = 15.5f
    private const val MIN_MAP_SIZE = 256
}

internal data class ShadowFrustumPlan(
    val focusX: Float,
    val focusY: Float,
    val focusZ: Float,
    val radiusMeters: Float,
    val texelWorldSize: Float,
)
