package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

/** Accepted structural line in source raster coordinates. */
internal data class RasterStructuralSegment(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
) {
    val lengthPx: Float
        get() {
            val dx = (x1 - x0).toFloat()
            val dy = (y1 - y0).toFloat()
            return sqrt(dx * dx + dy * dy)
        }

    fun endpoints(): List<Pair<Int, Int>> = listOf(x0 to y0, x1 to y1)
}

/**
 * Finds a conservative structural envelope from accepted wall evidence.
 *
 * The segment-aware path first identifies the dominant connected wall network. This suppresses a
 * common architectural-sheet failure where a long isolated dimension line or a disconnected title
 * box expands the crop and consequently distorts metric scale. It only uses the dominant component
 * when the evidence is decisive; sparse/ambiguous layouts fail closed to the broad endpoint envelope.
 */
internal object StructuralContentBounds {

    fun fromSegments(
        imageWidth: Int,
        imageHeight: Int,
        segments: List<RasterStructuralSegment>,
    ): PixelContentBounds {
        val broadPoints = segments.flatMap { it.endpoints() }
        val broad = fromPoints(imageWidth, imageHeight, broadPoints)
        if (
            imageWidth <= 0 || imageHeight <= 0 ||
            segments.size < MIN_COMPONENT_SEGMENTS
        ) return broad

        val tolerance = max(
            MIN_CONNECTION_TOLERANCE_PX,
            (min(imageWidth, imageHeight) * CONNECTION_TOLERANCE_FRACTION).roundToInt(),
        ).coerceAtMost(MAX_CONNECTION_TOLERANCE_PX)
        val adjacency = Array(segments.size) { ArrayList<Int>() }
        val perpendicular = Array(segments.size) { 0 }
        var connectionCount = 0

        for (i in 0 until segments.lastIndex) {
            for (j in i + 1 until segments.size) {
                if (!segmentsConnected(segments[i], segments[j], tolerance.toFloat())) continue
                adjacency[i] += j
                adjacency[j] += i
                connectionCount++
                if (directionCross(segments[i], segments[j]) >= MIN_PERPENDICULAR_CROSS) {
                    perpendicular[i]++
                    perpendicular[j]++
                }
            }
        }
        if (connectionCount < MIN_COMPONENT_CONNECTIONS) return broad

        val visited = BooleanArray(segments.size)
        val components = ArrayList<ComponentScore>()
        for (start in segments.indices) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val members = ArrayList<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                members += current
                adjacency[current].forEach { next ->
                    if (!visited[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
            }
            if (members.size < MIN_COMPONENT_SEGMENTS) continue
            val memberSegments = members.map { segments[it] }
            val points = memberSegments.flatMap { it.endpoints() }
            val raw = rawBounds(points, imageWidth, imageHeight) ?: continue
            if (
                raw.width < imageWidth * MIN_CONTENT_FRACTION ||
                raw.height < imageHeight * MIN_CONTENT_FRACTION
            ) continue

            val internalConnections = members.sumOf { adjacency[it].count { neighbor -> neighbor in members } } / 2
            val perpendicularRichness = members.sumOf { perpendicular[it] } * 0.5f
            val totalLength = memberSegments.sumOf { it.lengthPx.toDouble() }.toFloat()
            val lengthUnits = totalLength / min(imageWidth, imageHeight).coerceAtLeast(1).toFloat()
            val score =
                members.size * COMPONENT_SEGMENT_WEIGHT +
                    internalConnections * COMPONENT_CONNECTION_WEIGHT +
                    perpendicularRichness * COMPONENT_PERPENDICULAR_WEIGHT +
                    lengthUnits * COMPONENT_LENGTH_WEIGHT
            components += ComponentScore(
                memberIndices = members,
                bounds = raw,
                score = score,
            )
        }

        if (components.isEmpty()) return broad
        val ranked = components.sortedByDescending { it.score }
        val winner = ranked.first()
        if (winner.score < MIN_DOMINANT_COMPONENT_SCORE) return broad

        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && runnerUp.score >= winner.score * AMBIGUOUS_COMPONENT_RATIO) {
            // Two substantial disconnected structures may be two buildings, a garage, or a title
            // block. We cannot know which is architectural truth, so preserve the broad envelope.
            return broad
        }

        // Keep small disconnected structural islands when they are immediately adjacent to the main
        // building envelope (e.g. a short detached porch/column line), but not distant title blocks.
        val selected = winner.memberIndices.toMutableSet()
        val proximity = max(
            MIN_NEARBY_COMPONENT_PX,
            (min(imageWidth, imageHeight) * NEARBY_COMPONENT_FRACTION).roundToInt(),
        )
        ranked.drop(1).forEach { component ->
            if (
                component.score >= winner.score * NEARBY_COMPONENT_MIN_SCORE_RATIO &&
                boundsDistance(winner.bounds, component.bounds) <= proximity
            ) {
                selected += component.memberIndices
            }
        }

        val selectedPoints = selected
            .asSequence()
            .flatMap { segments[it].endpoints().asSequence() }
            .toList()
        val focused = fromPoints(imageWidth, imageHeight, selectedPoints)

        // A focused crop is only accepted if it meaningfully removes unrelated sheet area. If it is
        // almost the same as broad bounds, retaining broad avoids needless coordinate jitter.
        val broadArea = broad.width.toLong() * broad.height.toLong()
        val focusedArea = focused.width.toLong() * focused.height.toLong()
        if (broadArea <= 0L || focusedArea >= broadArea * MIN_MEANINGFUL_REDUCTION_RATIO) return broad
        return focused
    }

    fun fromPoints(
        imageWidth: Int,
        imageHeight: Int,
        points: List<Pair<Int, Int>>,
    ): PixelContentBounds {
        if (imageWidth <= 0 || imageHeight <= 0 || points.size < MIN_POINTS) {
            return PixelContentBounds.full(imageWidth, imageHeight)
        }

        val raw = rawBounds(points, imageWidth, imageHeight)
            ?: return PixelContentBounds.full(imageWidth, imageHeight)
        if (
            raw.width < imageWidth * MIN_CONTENT_FRACTION ||
            raw.height < imageHeight * MIN_CONTENT_FRACTION
        ) {
            return PixelContentBounds.full(imageWidth, imageHeight)
        }

        val padding = max(
            MIN_PADDING_PX,
            (min(raw.width, raw.height) * PADDING_FRACTION).roundToInt(),
        )
        return PixelContentBounds(
            left = (raw.left - padding).coerceAtLeast(0),
            top = (raw.top - padding).coerceAtLeast(0),
            rightExclusive = (raw.rightExclusive + padding).coerceAtMost(imageWidth),
            bottomExclusive = (raw.bottomExclusive + padding).coerceAtMost(imageHeight),
        )
    }

    private fun rawBounds(
        points: List<Pair<Int, Int>>,
        imageWidth: Int,
        imageHeight: Int,
    ): PixelContentBounds? {
        if (points.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        val minX = points.minOf { it.first }.coerceIn(0, imageWidth - 1)
        val maxX = points.maxOf { it.first }.coerceIn(0, imageWidth - 1)
        val minY = points.minOf { it.second }.coerceIn(0, imageHeight - 1)
        val maxY = points.maxOf { it.second }.coerceIn(0, imageHeight - 1)
        return PixelContentBounds(
            left = minX,
            top = minY,
            rightExclusive = (maxX + 1).coerceAtMost(imageWidth),
            bottomExclusive = (maxY + 1).coerceAtMost(imageHeight),
        )
    }

    private fun segmentsConnected(
        a: RasterStructuralSegment,
        b: RasterStructuralSegment,
        tolerance: Float,
    ): Boolean {
        if (segmentIntersection(a, b)) return true
        val toleranceSq = tolerance * tolerance
        return pointSegmentDistanceSq(a.x0.toFloat(), a.y0.toFloat(), b) <= toleranceSq ||
            pointSegmentDistanceSq(a.x1.toFloat(), a.y1.toFloat(), b) <= toleranceSq ||
            pointSegmentDistanceSq(b.x0.toFloat(), b.y0.toFloat(), a) <= toleranceSq ||
            pointSegmentDistanceSq(b.x1.toFloat(), b.y1.toFloat(), a) <= toleranceSq
    }

    private fun segmentIntersection(a: RasterStructuralSegment, b: RasterStructuralSegment): Boolean {
        val ax = a.x0.toFloat()
        val ay = a.y0.toFloat()
        val arx = (a.x1 - a.x0).toFloat()
        val ary = (a.y1 - a.y0).toFloat()
        val bx = b.x0.toFloat()
        val by = b.y0.toFloat()
        val bsx = (b.x1 - b.x0).toFloat()
        val bsy = (b.y1 - b.y0).toFloat()
        val denominator = arx * bsy - ary * bsx
        if (abs(denominator) < 1e-5f) return false
        val qpx = bx - ax
        val qpy = by - ay
        val t = (qpx * bsy - qpy * bsx) / denominator
        val u = (qpx * ary - qpy * arx) / denominator
        return t in 0f..1f && u in 0f..1f
    }

    private fun pointSegmentDistanceSq(px: Float, py: Float, segment: RasterStructuralSegment): Float {
        val ax = segment.x0.toFloat()
        val ay = segment.y0.toFloat()
        val vx = (segment.x1 - segment.x0).toFloat()
        val vy = (segment.y1 - segment.y0).toFloat()
        val lengthSq = vx * vx + vy * vy
        if (lengthSq <= 1e-5f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        val t = (((px - ax) * vx + (py - ay) * vy) / lengthSq).coerceIn(0f, 1f)
        val dx = px - (ax + vx * t)
        val dy = py - (ay + vy * t)
        return dx * dx + dy * dy
    }

    private fun directionCross(a: RasterStructuralSegment, b: RasterStructuralSegment): Float {
        val ax = (a.x1 - a.x0).toFloat()
        val ay = (a.y1 - a.y0).toFloat()
        val bx = (b.x1 - b.x0).toFloat()
        val by = (b.y1 - b.y0).toFloat()
        val aLength = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-5f)
        val bLength = sqrt(bx * bx + by * by).coerceAtLeast(1e-5f)
        return abs(ax * by - ay * bx) / (aLength * bLength)
    }

    private fun boundsDistance(a: PixelContentBounds, b: PixelContentBounds): Int {
        val dx = when {
            a.rightExclusive < b.left -> b.left - a.rightExclusive
            b.rightExclusive < a.left -> a.left - b.rightExclusive
            else -> 0
        }
        val dy = when {
            a.bottomExclusive < b.top -> b.top - a.bottomExclusive
            b.bottomExclusive < a.top -> a.top - b.bottomExclusive
            else -> 0
        }
        return sqrt((dx * dx + dy * dy).toFloat()).roundToInt()
    }

    private data class ComponentScore(
        val memberIndices: List<Int>,
        val bounds: PixelContentBounds,
        val score: Float,
    )

    private const val MIN_POINTS = 8
    private const val MIN_COMPONENT_SEGMENTS = 4
    private const val MIN_COMPONENT_CONNECTIONS = 3
    private const val MIN_CONTENT_FRACTION = 0.18f
    private const val PADDING_FRACTION = 0.025f
    private const val MIN_PADDING_PX = 5

    private const val CONNECTION_TOLERANCE_FRACTION = 0.004f
    private const val MIN_CONNECTION_TOLERANCE_PX = 3
    private const val MAX_CONNECTION_TOLERANCE_PX = 14
    private const val MIN_PERPENDICULAR_CROSS = 0.35f

    private const val COMPONENT_SEGMENT_WEIGHT = 2.4f
    private const val COMPONENT_CONNECTION_WEIGHT = 1.1f
    private const val COMPONENT_PERPENDICULAR_WEIGHT = 2.8f
    private const val COMPONENT_LENGTH_WEIGHT = 1.2f
    private const val MIN_DOMINANT_COMPONENT_SCORE = 16f
    private const val AMBIGUOUS_COMPONENT_RATIO = 0.72f

    private const val NEARBY_COMPONENT_FRACTION = 0.035f
    private const val MIN_NEARBY_COMPONENT_PX = 8
    private const val NEARBY_COMPONENT_MIN_SCORE_RATIO = 0.16f
    private const val MIN_MEANINGFUL_REDUCTION_RATIO = 0.96
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
