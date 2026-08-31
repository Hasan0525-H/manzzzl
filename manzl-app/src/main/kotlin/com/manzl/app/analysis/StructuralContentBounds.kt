package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel-space bounds for the actual structural drawing, excluding unrelated white margins. */
internal data class PixelContentBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    val width: Int get() = (rightExclusive - left).coerceAtLeast(1)
    val height: Int get() = (bottomExclusive - top).coerceAtLeast(1)

    fun normalized(imageWidth: Int, imageHeight: Int): NormalizedContentBounds {
        if (imageWidth <= 0 || imageHeight <= 0) return NormalizedContentBounds.FULL
        return NormalizedContentBounds(
            left = (left / imageWidth.toFloat()).coerceIn(0f, 1f),
            top = (top / imageHeight.toFloat()).coerceIn(0f, 1f),
            right = (rightExclusive / imageWidth.toFloat()).coerceIn(0f, 1f),
            bottom = (bottomExclusive / imageHeight.toFloat()).coerceIn(0f, 1f),
        ).validated()
    }

    companion object {
        fun full(imageWidth: Int, imageHeight: Int): PixelContentBounds = PixelContentBounds(
            left = 0,
            top = 0,
            rightExclusive = imageWidth.coerceAtLeast(1),
            bottomExclusive = imageHeight.coerceAtLeast(1),
        )
    }
}

/**
 * Normalized source-image bounds persisted with FloorPlan so every semantic provider uses exactly
 * the same image↔plan coordinate system as structural extraction.
 */
internal data class NormalizedContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun validated(): NormalizedContentBounds {
        val l = left.coerceIn(0f, 1f - MIN_NORMALIZED_SPAN)
        val t = top.coerceIn(0f, 1f - MIN_NORMALIZED_SPAN)
        val r = right.coerceIn(l + MIN_NORMALIZED_SPAN, 1f)
        val b = bottom.coerceIn(t + MIN_NORMALIZED_SPAN, 1f)
        return NormalizedContentBounds(l, t, r, b)
    }

    fun toPixels(imageWidth: Int, imageHeight: Int): PixelContentBounds {
        if (imageWidth <= 0 || imageHeight <= 0) return PixelContentBounds.full(imageWidth, imageHeight)
        val safe = validated()
        val leftPx = (safe.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val topPx = (safe.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
        val rightPx = (safe.right * imageWidth).roundToInt().coerceIn(leftPx + 1, imageWidth)
        val bottomPx = (safe.bottom * imageHeight).roundToInt().coerceIn(topPx + 1, imageHeight)
        return PixelContentBounds(leftPx, topPx, rightPx, bottomPx)
    }

    companion object {
        val FULL = NormalizedContentBounds(0f, 0f, 1f, 1f)
        private const val MIN_NORMALIZED_SPAN = 0.02f
    }
}

/**
 * Finds a conservative structural envelope from already accepted wall-line endpoints.
 *
 * Text, title blocks and page margins cannot enlarge this envelope because they never enter the
 * endpoint set. A small adaptive padding preserves wall thickness and opening symbols. Suspiciously
 * tiny envelopes fail closed to the full image rather than cropping away real architecture.
 */
internal object StructuralContentBounds {

    fun fromPoints(
        imageWidth: Int,
        imageHeight: Int,
        points: List<Pair<Int, Int>>,
    ): PixelContentBounds {
        if (imageWidth <= 0 || imageHeight <= 0 || points.size < MIN_POINTS) {
            return PixelContentBounds.full(imageWidth, imageHeight)
        }

        val minX = points.minOf { it.first }.coerceIn(0, imageWidth - 1)
        val maxX = points.maxOf { it.first }.coerceIn(0, imageWidth - 1)
        val minY = points.minOf { it.second }.coerceIn(0, imageHeight - 1)
        val maxY = points.maxOf { it.second }.coerceIn(0, imageHeight - 1)
        val rawWidth = (maxX - minX + 1).coerceAtLeast(1)
        val rawHeight = (maxY - minY + 1).coerceAtLeast(1)

        if (
            rawWidth < imageWidth * MIN_CONTENT_FRACTION ||
            rawHeight < imageHeight * MIN_CONTENT_FRACTION
        ) {
            return PixelContentBounds.full(imageWidth, imageHeight)
        }

        val padding = max(
            MIN_PADDING_PX,
            (min(rawWidth, rawHeight) * PADDING_FRACTION).roundToInt(),
        )
        return PixelContentBounds(
            left = (minX - padding).coerceAtLeast(0),
            top = (minY - padding).coerceAtLeast(0),
            rightExclusive = (maxX + 1 + padding).coerceAtMost(imageWidth),
            bottomExclusive = (maxY + 1 + padding).coerceAtMost(imageHeight),
        )
    }

    private const val MIN_POINTS = 8
    private const val MIN_CONTENT_FRACTION = 0.18f
    private const val PADDING_FRACTION = 0.025f
    private const val MIN_PADDING_PX = 5
}

/** Shared transform that prevents crop/white-margin drift across OCR, CV and 3D evidence passes. */
internal class PlanRasterTransform private constructor(
    private val plan: FloorPlan,
    val bounds: PixelContentBounds,
) {
    val pixelsPerMeterX: Float = bounds.width / plan.widthMeters.coerceAtLeast(EPSILON)
    val pixelsPerMeterZ: Float = bounds.height / plan.depthMeters.coerceAtLeast(EPSILON)

    fun imageToPlan(xPx: Float, yPx: Float): Vec2 {
        val nx = ((xPx - bounds.left) / bounds.width.toFloat()).coerceIn(-0.25f, 1.25f)
        val ny = ((yPx - bounds.top) / bounds.height.toFloat()).coerceIn(-0.25f, 1.25f)
        return Vec2(
            x = (nx - 0.5f) * plan.widthMeters,
            z = (ny - 0.5f) * plan.depthMeters,
        )
    }

    fun planToImage(point: Vec2): Pair<Float, Float> {
        val nx = point.x / plan.widthMeters.coerceAtLeast(EPSILON) + 0.5f
        val nz = point.z / plan.depthMeters.coerceAtLeast(EPSILON) + 0.5f
        return Pair(
            bounds.left + nx * bounds.width,
            bounds.top + nz * bounds.height,
        )
    }

    companion object {
        fun forImage(plan: FloorPlan, imageWidth: Int, imageHeight: Int): PlanRasterTransform {
            val normalized = NormalizedContentBounds(
                left = plan.contentLeftFraction,
                top = plan.contentTopFraction,
                right = plan.contentRightFraction,
                bottom = plan.contentBottomFraction,
            )
            return PlanRasterTransform(plan, normalized.toPixels(imageWidth, imageHeight))
        }

        private const val EPSILON = 0.000001f
    }
}
