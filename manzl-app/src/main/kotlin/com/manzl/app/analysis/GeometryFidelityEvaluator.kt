package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssue
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Independent raster-vs-geometry quality gate.
 *
 * The evaluator does not reuse analyzer confidence. It rasterizes the measured wall faces back into
 * image space and compares them with long line-like source evidence. This catches the failure mode
 * where a pipeline returns a clean-looking but incomplete/simplified house. Text and short symbols
 * are suppressed by a directional continuity filter before coverage is measured.
 *
 * In addition to aggregate scores it localizes the strongest disagreement regions. Those regions are
 * review/correction hints only; they never relax PASS thresholds and never modify geometry.
 */
internal object GeometryFidelityEvaluator {

    fun evaluate(
        structuralMask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        plan: FloorPlan,
    ): GeometryFidelityReport {
        if (
            imageWidth <= 2 || imageHeight <= 2 ||
            structuralMask.size != imageWidth * imageHeight ||
            plan.walls.size < MIN_WALL_COUNT
        ) {
            return blocked()
        }

        val transform = PlanRasterTransform.forImage(plan, imageWidth, imageHeight)
        val bounds = transform.bounds
        val sourceEvidence = buildLineEvidence(
            mask = structuralMask,
            width = imageWidth,
            height = imageHeight,
            bounds = bounds,
        )
        val predicted = rasterizeWalls(
            width = imageWidth,
            height = imageHeight,
            plan = plan,
            transform = transform,
        )

        val sourceCount = countTrue(sourceEvidence, bounds, imageWidth)
        val predictedCount = countTrue(predicted, bounds, imageWidth)
        if (sourceCount < MIN_EVIDENCE_PIXELS || predictedCount < MIN_EVIDENCE_PIXELS) {
            return blocked()
        }

        val tolerance = max(1, min(bounds.width, bounds.height) / TOLERANCE_DIVISOR)
            .coerceAtMost(MAX_TOLERANCE_PX)
        val sourceDilated = dilate(sourceEvidence, imageWidth, imageHeight, bounds, tolerance)
        val predictedDilated = dilate(predicted, imageWidth, imageHeight, bounds, tolerance)

        var sourceMatched = 0
        var predictedMatched = 0
        for (y in bounds.top until bounds.bottomExclusive) {
            val row = y * imageWidth
            for (x in bounds.left until bounds.rightExclusive) {
                val index = row + x
                if (sourceEvidence[index] && predictedDilated[index]) sourceMatched++
                if (predicted[index] && sourceDilated[index]) predictedMatched++
            }
        }

        val coverage = (sourceMatched / sourceCount.toFloat()).coerceIn(0f, 1f)
        val precision = (predictedMatched / predictedCount.toFloat()).coerceIn(0f, 1f)
        val endpointSupport = endpointSupport(
            rawMask = structuralMask,
            width = imageWidth,
            height = imageHeight,
            plan = plan,
            transform = transform,
        )
        val score = (
            coverage * COVERAGE_WEIGHT +
                precision * PRECISION_WEIGHT +
                endpointSupport * ENDPOINT_WEIGHT
            ).coerceIn(0f, 1f)

        val status = when {
            score >= PASS_SCORE &&
                coverage >= PASS_COVERAGE &&
                precision >= PASS_PRECISION &&
                endpointSupport >= PASS_ENDPOINT_SUPPORT -> GeometryFidelityStatus.PASS

            score >= REVIEW_SCORE &&
                coverage >= REVIEW_COVERAGE &&
                precision >= REVIEW_PRECISION -> GeometryFidelityStatus.REVIEW_REQUIRED

            else -> GeometryFidelityStatus.BLOCKED
        }

        val issues = localizeIssues(
            sourceEvidence = sourceEvidence,
            predicted = predicted,
            sourceDilated = sourceDilated,
            predictedDilated = predictedDilated,
            width = imageWidth,
            height = imageHeight,
            bounds = bounds,
        )

        return GeometryFidelityReport(
            score = score,
            wallCoverage = coverage,
            wallPrecision = precision,
            endpointSupport = endpointSupport,
            status = status,
            issues = issues,
        )
    }

    private fun localizeIssues(
        sourceEvidence: BooleanArray,
        predicted: BooleanArray,
        sourceDilated: BooleanArray,
        predictedDilated: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
    ): List<GeometryFidelityIssue> {
        if (bounds.width <= 0 || bounds.height <= 0) return emptyList()
        val columns = (bounds.width / TARGET_TILE_SIZE_PX).coerceIn(2, MAX_ISSUE_GRID_AXIS)
        val rows = (bounds.height / TARGET_TILE_SIZE_PX).coerceIn(2, MAX_ISSUE_GRID_AXIS)
        val tileWidth = max(1, ceil(bounds.width / columns.toFloat()).toInt())
        val tileHeight = max(1, ceil(bounds.height / rows.toFloat()).toInt())
        val issues = ArrayList<GeometryFidelityIssue>()

        for (rowIndex in 0 until rows) {
            val top = bounds.top + rowIndex * tileHeight
            val bottom = min(bounds.bottomExclusive, top + tileHeight)
            if (bottom <= top) continue
            for (columnIndex in 0 until columns) {
                val left = bounds.left + columnIndex * tileWidth
                val right = min(bounds.rightExclusive, left + tileWidth)
                if (right <= left) continue

                var source = 0
                var sourceMatched = 0
                var predictedCount = 0
                var predictedMatched = 0
                for (y in top until bottom) {
                    val base = y * width
                    for (x in left until right) {
                        val index = base + x
                        if (sourceEvidence[index]) {
                            source++
                            if (predictedDilated[index]) sourceMatched++
                        }
                        if (predicted[index]) {
                            predictedCount++
                            if (sourceDilated[index]) predictedMatched++
                        }
                    }
                }

                val tileArea = (right - left) * (bottom - top)
                val minLocalEvidence = max(MIN_LOCAL_EVIDENCE_PIXELS, tileArea / LOCAL_EVIDENCE_AREA_DIVISOR)
                if (source >= minLocalEvidence) {
                    val localCoverage = sourceMatched / source.toFloat()
                    val deficit = (1f - localCoverage).coerceIn(0f, 1f)
                    if (deficit >= LOCAL_ISSUE_DEFICIT) {
                        issues += issue(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            width = width,
                            height = height,
                            kind = GeometryFidelityIssueKind.MISSING_SOURCE,
                            severity = deficit,
                        )
                    }
                }
                if (predictedCount >= minLocalEvidence) {
                    val localPrecision = predictedMatched / predictedCount.toFloat()
                    val deficit = (1f - localPrecision).coerceIn(0f, 1f)
                    if (deficit >= LOCAL_ISSUE_DEFICIT) {
                        issues += issue(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            width = width,
                            height = height,
                            kind = GeometryFidelityIssueKind.EXTRA_GEOMETRY,
                            severity = deficit,
                        )
                    }
                }
            }
        }

        return issues
            .sortedByDescending { it.severity }
            .take(MAX_LOCAL_ISSUES)
    }

    private fun issue(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        width: Int,
        height: Int,
        kind: GeometryFidelityIssueKind,
        severity: Float,
    ) = GeometryFidelityIssue(
        leftFraction = (left / width.toFloat()).coerceIn(0f, 1f),
        topFraction = (top / height.toFloat()).coerceIn(0f, 1f),
        rightFraction = (right / width.toFloat()).coerceIn(0f, 1f),
        bottomFraction = (bottom / height.toFloat()).coerceIn(0f, 1f),
        kind = kind,
        severity = severity.coerceIn(0f, 1f),
    )

    private fun buildLineEvidence(
        mask: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
    ): BooleanArray {
        val result = BooleanArray(mask.size)
        val directions = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1),
            intArrayOf(2, 1),
            intArrayOf(1, 2),
            intArrayOf(2, -1),
            intArrayOf(1, -2),
        )

        for (y in bounds.top until bounds.bottomExclusive) {
            val row = y * width
            for (x in bounds.left until bounds.rightExclusive) {
                val index = row + x
                if (!mask[index]) continue

                var structural = false
                for (direction in directions) {
                    var hits = 0
                    var valid = 0
                    for (step in -LINE_RADIUS_STEPS..LINE_RADIUS_STEPS) {
                        val sx = x + direction[0] * step
                        val sy = y + direction[1] * step
                        if (
                            sx < bounds.left || sx >= bounds.rightExclusive ||
                            sy < bounds.top || sy >= bounds.bottomExclusive ||
                            sx !in 0 until width || sy !in 0 until height
                        ) continue
                        valid++
                        if (mask[sy * width + sx]) hits++
                    }
                    if (
                        valid >= MIN_DIRECTION_SAMPLES &&
                        hits >= MIN_DIRECTION_HITS &&
                        hits / valid.toFloat() >= MIN_DIRECTION_RATIO
                    ) {
                        structural = true
                        break
                    }
                }
                result[index] = structural
            }
        }
        return result
    }

    private fun rasterizeWalls(
        width: Int,
        height: Int,
        plan: FloorPlan,
        transform: PlanRasterTransform,
    ): BooleanArray {
        val result = BooleanArray(width * height)
        val pixelsPerMeter = (transform.pixelsPerMeterX + transform.pixelsPerMeterZ) * 0.5f

        plan.walls.forEach { wall ->
            val (ax, ay) = transform.planToImage(wall.start)
            val (bx, by) = transform.planToImage(wall.end)
            val radius = max(MIN_RENDER_RADIUS_PX, wall.thicknessMeters * pixelsPerMeter * 0.5f)
            val minX = floor(min(ax, bx) - radius - 1f).toInt().coerceIn(0, width - 1)
            val maxX = ceil(max(ax, bx) + radius + 1f).toInt().coerceIn(0, width - 1)
            val minY = floor(min(ay, by) - radius - 1f).toInt().coerceIn(0, height - 1)
            val maxY = ceil(max(ay, by) + radius + 1f).toInt().coerceIn(0, height - 1)
            val radiusSq = radius * radius

            for (y in minY..maxY) {
                val row = y * width
                for (x in minX..maxX) {
                    if (pointSegmentDistanceSquared(x + 0.5f, y + 0.5f, ax, ay, bx, by) <= radiusSq) {
                        result[row + x] = true
                    }
                }
            }
        }
        return result
    }

    private fun endpointSupport(
        rawMask: BooleanArray,
        width: Int,
        height: Int,
        plan: FloorPlan,
        transform: PlanRasterTransform,
    ): Float {
        if (plan.walls.isEmpty()) return 0f
        val pixelsPerMeter = (transform.pixelsPerMeterX + transform.pixelsPerMeterZ) * 0.5f
        var supported = 0
        var total = 0

        plan.walls.forEach { wall ->
            val radius = max(
                MIN_ENDPOINT_RADIUS_PX,
                (wall.thicknessMeters * pixelsPerMeter * ENDPOINT_RADIUS_MULTIPLIER).roundToInt(),
            ).coerceAtMost(MAX_ENDPOINT_RADIUS_PX)
            listOf(wall.start, wall.end).forEach { endpoint ->
                val (px, py) = transform.planToImage(endpoint)
                total++
                if (hasInkNear(rawMask, width, height, px.roundToInt(), py.roundToInt(), radius)) {
                    supported++
                }
            }
        }
        return if (total == 0) 0f else supported / total.toFloat()
    }

    private fun hasInkNear(
        mask: BooleanArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Boolean {
        val rSq = radius * radius
        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(width - 1)
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(height - 1)
        for (y in minY..maxY) {
            val row = y * width
            val dy = y - cy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy * dy <= rSq && mask[row + x]) return true
            }
        }
        return false
    }

    private fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
        radius: Int,
    ): BooleanArray {
        if (radius <= 0) return source.copyOf()
        val result = BooleanArray(source.size)
        val offsets = buildList {
            val rSq = radius * radius
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy <= rSq) add(dx to dy)
                }
            }
        }
        for (y in bounds.top until bounds.bottomExclusive) {
            val row = y * width
            for (x in bounds.left until bounds.rightExclusive) {
                if (!source[row + x]) continue
                offsets.forEach { (dx, dy) ->
                    val tx = x + dx
                    val ty = y + dy
                    if (tx in 0 until width && ty in 0 until height) {
                        result[ty * width + tx] = true
                    }
                }
            }
        }
        return result
    }

    private fun countTrue(mask: BooleanArray, bounds: PixelContentBounds, width: Int): Int {
        var count = 0
        for (y in bounds.top until bounds.bottomExclusive) {
            val row = y * width
            for (x in bounds.left until bounds.rightExclusive) {
                if (mask[row + x]) count++
            }
        }
        return count
    }

    private fun pointSegmentDistanceSquared(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val lengthSq = vx * vx + vy * vy
        if (lengthSq <= 0.000001f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        val t = (((px - ax) * vx + (py - ay) * vy) / lengthSq).coerceIn(0f, 1f)
        val qx = ax + vx * t
        val qy = ay + vy * t
        val dx = px - qx
        val dy = py - qy
        return dx * dx + dy * dy
    }

    private fun blocked() = GeometryFidelityReport(
        score = 0f,
        wallCoverage = 0f,
        wallPrecision = 0f,
        endpointSupport = 0f,
        status = GeometryFidelityStatus.BLOCKED,
        issues = emptyList(),
    )

    private const val MIN_WALL_COUNT = 4
    private const val MIN_EVIDENCE_PIXELS = 24
    private const val LINE_RADIUS_STEPS = 5
    private const val MIN_DIRECTION_SAMPLES = 7
    private const val MIN_DIRECTION_HITS = 6
    private const val MIN_DIRECTION_RATIO = 0.70f
    private const val TOLERANCE_DIVISOR = 420
    private const val MAX_TOLERANCE_PX = 4
    private const val MIN_RENDER_RADIUS_PX = 1.25f
    private const val MIN_ENDPOINT_RADIUS_PX = 3
    private const val MAX_ENDPOINT_RADIUS_PX = 14
    private const val ENDPOINT_RADIUS_MULTIPLIER = 0.75f

    private const val TARGET_TILE_SIZE_PX = 180
    private const val MAX_ISSUE_GRID_AXIS = 7
    private const val MIN_LOCAL_EVIDENCE_PIXELS = 8
    private const val LOCAL_EVIDENCE_AREA_DIVISOR = 240
    private const val LOCAL_ISSUE_DEFICIT = 0.34f
    private const val MAX_LOCAL_ISSUES = 10

    private const val COVERAGE_WEIGHT = 0.48f
    private const val PRECISION_WEIGHT = 0.37f
    private const val ENDPOINT_WEIGHT = 0.15f

    private const val PASS_SCORE = 0.70f
    private const val PASS_COVERAGE = 0.62f
    private const val PASS_PRECISION = 0.70f
    private const val PASS_ENDPOINT_SUPPORT = 0.72f
    private const val REVIEW_SCORE = 0.54f
    private const val REVIEW_COVERAGE = 0.45f
    private const val REVIEW_PRECISION = 0.52f
}
