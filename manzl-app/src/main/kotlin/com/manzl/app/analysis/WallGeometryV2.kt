package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry Engine v2 wall refinement.
 *
 * The legacy scanner is still useful for robust orthogonal context, but it used a fixed 18 cm wall
 * thickness and discarded non-axis walls. This pass measures wall-face separation directly from the
 * raster and adds only high-support arbitrary-angle wall candidates. It never invents geometry from
 * style priors and it never bridges opening-sized gaps.
 */
internal object WallGeometryV2 {

    fun refine(
        structuralMask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        bounds: PixelContentBounds,
        pxToMeter: Float,
        baseWalls: List<WallSegment>,
    ): List<WallSegment> {
        if (
            structuralMask.size != imageWidth * imageHeight ||
            imageWidth <= 2 || imageHeight <= 2 ||
            pxToMeter <= 0f
        ) return baseWalls

        val measured = baseWalls.map { wall ->
            val measuredThickness = measureThickness(
                mask = structuralMask,
                width = imageWidth,
                height = imageHeight,
                bounds = bounds,
                pxToMeter = pxToMeter,
                wall = wall,
            )
            if (measuredThickness == null) wall else wall.copy(thicknessMeters = measuredThickness)
        }

        val diagonalCandidates = detectDiagonalWalls(
            mask = structuralMask,
            width = imageWidth,
            height = imageHeight,
            bounds = bounds,
            pxToMeter = pxToMeter,
        )
        val diagonals = mergeDiagonalCandidates(diagonalCandidates)
            .filter { candidate ->
                val length = distance(candidate.start, candidate.end)
                if (length < MIN_DIAGONAL_OUTPUT_METERS) return@filter false
                val touchesMeasured = listOf(candidate.start, candidate.end).count { endpoint ->
                    measured.any { wall -> pointSegmentDistance(endpoint, wall.start, wall.end) <= CONNECTION_DISTANCE_METERS }
                }
                length >= LONG_STANDALONE_DIAGONAL_METERS || touchesMeasured >= 1
            }
            .filterNot { candidate -> measured.any { wall -> nearlySameWall(candidate, wall) } }

        return measured + diagonals
    }

    private fun measureThickness(
        mask: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
        pxToMeter: Float,
        wall: WallSegment,
    ): Float? {
        val a = planToPixel(wall.start, bounds, pxToMeter)
        val b = planToPixel(wall.end, bounds, pxToMeter)
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = sqrt(dx * dx + dy * dy)
        if (length < MIN_THICKNESS_CONTEXT_PX) return null
        val ux = dx / length
        val uy = dy / length
        val nx = -uy
        val ny = ux

        val samples = ArrayList<Float>()
        for (t in THICKNESS_SAMPLE_POSITIONS) {
            val cx = a.first + dx * t
            val cy = a.second + dy * t
            val darkOffsets = ArrayList<Int>()
            for (offset in -MAX_THICKNESS_HALF_PX..MAX_THICKNESS_HALF_PX) {
                var support = 0
                for (along in -ALONG_SAMPLE_RADIUS..ALONG_SAMPLE_RADIUS) {
                    val sx = (cx + nx * offset + ux * along).toInt()
                    val sy = (cy + ny * offset + uy * along).toInt()
                    if (
                        sx in bounds.left until bounds.rightExclusive &&
                        sy in bounds.top until bounds.bottomExclusive &&
                        sx in 0 until width && sy in 0 until height &&
                        mask[sy * width + sx]
                    ) support++
                }
                if (support >= MIN_PROFILE_SUPPORT) darkOffsets += offset
            }
            if (darkOffsets.size < MIN_PROFILE_DARK_OFFSETS) continue
            val first = darkOffsets.first()
            val last = darkOffsets.last()
            val span = (last - first + 1).toFloat()
            if (span !in MIN_THICKNESS_SPAN_PX..MAX_THICKNESS_SPAN_PX) continue

            val hasNegative = darkOffsets.any { it <= -MIN_OPPOSITE_FACE_OFFSET_PX }
            val hasPositive = darkOffsets.any { it >= MIN_OPPOSITE_FACE_OFFSET_PX }
            val looksLikeSingleSolidStroke = span <= MAX_SINGLE_STROKE_SPAN_PX
            if (!(hasNegative && hasPositive) && !looksLikeSingleSolidStroke) continue
            samples += span
        }

        if (samples.size < MIN_THICKNESS_SAMPLES) return null
        samples.sort()
        val medianPixels = samples[samples.size / 2]
        val meters = medianPixels * pxToMeter
        if (meters !in MIN_MEASURED_THICKNESS_METERS..MAX_MEASURED_THICKNESS_METERS) return null
        return meters
    }

    private fun detectDiagonalWalls(
        mask: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
        pxToMeter: Float,
    ): List<WallSegment> {
        val minSpan = min(bounds.width, bounds.height)
        val tile = max(MIN_TILE_PX, minSpan / TILE_DIVISOR).coerceAtMost(MAX_TILE_PX)
        val step = max(MIN_TILE_STEP_PX, tile / 2)
        val result = ArrayList<WallSegment>()

        var top = bounds.top
        while (top < bounds.bottomExclusive) {
            val bottom = min(bounds.bottomExclusive, top + tile)
            var left = bounds.left
            while (left < bounds.rightExclusive) {
                val right = min(bounds.rightExclusive, left + tile)
                val points = ArrayList<PixelPoint>()
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        if (mask[y * width + x]) points += PixelPoint(x.toFloat(), y.toFloat())
                        x += PCA_PIXEL_STEP
                    }
                    y += PCA_PIXEL_STEP
                }

                candidateFromTile(points, tile, bounds, pxToMeter, mask, width, height)?.let(result::add)
                left += step
            }
            top += step
        }
        return result
    }

    private fun candidateFromTile(
        points: List<PixelPoint>,
        tileSize: Int,
        bounds: PixelContentBounds,
        pxToMeter: Float,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ): WallSegment? {
        if (points.size < MIN_PCA_POINTS) return null
        val meanX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val meanY = points.sumOf { it.y.toDouble() }.toFloat() / points.size

        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        points.forEach { point ->
            val dx = (point.x - meanX).toDouble()
            val dy = (point.y - meanY).toDouble()
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        xx /= points.size
        yy /= points.size
        xy /= points.size

        val trace = xx + yy
        val root = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val lambda1 = (trace + root) * 0.5
        val lambda2 = max(0.0, (trace - root) * 0.5)
        if (lambda1 <= 1.0) return null
        val anisotropy = lambda1 / (lambda2 + 0.65)
        if (anisotropy < MIN_PCA_ANISOTROPY) return null

        val angle = 0.5 * atan2(2.0 * xy, xx - yy)
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val degrees = normalizeHalfTurnDegrees(Math.toDegrees(angle).toFloat())
        val axisDistance = minOf(
            abs(degrees),
            abs(degrees - 90f),
            abs(degrees - 180f),
        )
        if (axisDistance < MIN_AXIS_ANGLE_DISTANCE_DEGREES) return null

        val projections = points.map { point ->
            (point.x - meanX) * ux + (point.y - meanY) * uy
        }.sorted()
        if (projections.size < MIN_PCA_POINTS) return null
        val low = projections[(projections.lastIndex * PROJECTION_LOW_QUANTILE).toInt()]
        val high = projections[(projections.lastIndex * PROJECTION_HIGH_QUANTILE).toInt()]
        val lengthPx = high - low
        if (lengthPx < tileSize * MIN_TILE_SPAN_RATIO || lengthPx * pxToMeter < MIN_DIAGONAL_CANDIDATE_METERS) {
            return null
        }

        val thicknessPx = sqrt(max(0.0, lambda2) * 12.0).toFloat()
            .coerceIn(MIN_DIAGONAL_THICKNESS_PX, MAX_DIAGONAL_THICKNESS_PX)
        val support = lineSupport(
            mask = mask,
            width = width,
            height = height,
            bounds = bounds,
            meanX = meanX,
            meanY = meanY,
            ux = ux,
            uy = uy,
            from = low,
            to = high,
            crossRadius = ceil(thicknessPx * 0.65f + 2f).toInt(),
        )
        if (support < MIN_DIAGONAL_SUPPORT) return null

        val startPixel = PixelPoint(meanX + ux * low, meanY + uy * low)
        val endPixel = PixelPoint(meanX + ux * high, meanY + uy * high)
        val thicknessMeters = (thicknessPx * pxToMeter)
            .coerceIn(MIN_MEASURED_THICKNESS_METERS, MAX_MEASURED_THICKNESS_METERS)
        val confidence = (
            0.62f +
                support * 0.24f +
                ((anisotropy / 18.0).coerceIn(0.0, 1.0).toFloat() * 0.10f)
            ).coerceIn(0f, 0.96f)

        return WallSegment(
            start = pixelToPlan(startPixel, bounds, pxToMeter),
            end = pixelToPlan(endPixel, bounds, pxToMeter),
            thicknessMeters = thicknessMeters,
            confidence = confidence,
        )
    }

    private fun lineSupport(
        mask: BooleanArray,
        width: Int,
        height: Int,
        bounds: PixelContentBounds,
        meanX: Float,
        meanY: Float,
        ux: Float,
        uy: Float,
        from: Float,
        to: Float,
        crossRadius: Int,
    ): Float {
        val nx = -uy
        val ny = ux
        val sampleCount = max(MIN_SUPPORT_SAMPLES, ((to - from) / SUPPORT_SAMPLE_SPACING_PX).toInt())
        var supported = 0
        for (index in 0 until sampleCount) {
            val t = if (sampleCount == 1) 0.5f else index / (sampleCount - 1f)
            val projection = from + (to - from) * t
            val cx = meanX + ux * projection
            val cy = meanY + uy * projection
            var found = false
            for (cross in -crossRadius..crossRadius) {
                if (found) break
                for (along in -1..1) {
                    val x = (cx + nx * cross + ux * along).toInt()
                    val y = (cy + ny * cross + uy * along).toInt()
                    if (
                        x in bounds.left until bounds.rightExclusive &&
                        y in bounds.top until bounds.bottomExclusive &&
                        x in 0 until width && y in 0 until height &&
                        mask[y * width + x]
                    ) {
                        found = true
                        break
                    }
                }
            }
            if (found) supported++
        }
        return supported / sampleCount.toFloat()
    }

    private fun mergeDiagonalCandidates(source: List<WallSegment>): List<WallSegment> {
        if (source.isEmpty()) return emptyList()
        val groups = ArrayList<DiagonalGroup>()
        source.sortedByDescending { distance(it.start, it.end) }.forEach { candidate ->
            val matching = groups.firstOrNull { it.canMerge(candidate) }
            if (matching == null) groups += DiagonalGroup(candidate) else matching.merge(candidate)
        }
        return groups.map { it.toWall() }
            .filter { it.confidence >= MIN_OUTPUT_CONFIDENCE }
    }

    private class DiagonalGroup(seed: WallSegment) {
        private val origin = seed.start
        private var ux: Float
        private var uz: Float
        private var from = 0f
        private var to: Float
        private var thickness = seed.thicknessMeters
        private var confidence = seed.confidence
        private var weight = distance(seed.start, seed.end).coerceAtLeast(0.01f)

        init {
            val length = distance(seed.start, seed.end).coerceAtLeast(0.0001f)
            ux = (seed.end.x - seed.start.x) / length
            uz = (seed.end.z - seed.start.z) / length
            to = length
        }

        fun canMerge(candidate: WallSegment): Boolean {
            val length = distance(candidate.start, candidate.end)
            if (length <= 0.0001f) return false
            val cux = (candidate.end.x - candidate.start.x) / length
            val cuz = (candidate.end.z - candidate.start.z) / length
            val alignment = abs(ux * cux + uz * cuz)
            if (alignment < COS_MAX_MERGE_ANGLE) return false

            val midpoint = Vec2(
                (candidate.start.x + candidate.end.x) * 0.5f,
                (candidate.start.z + candidate.end.z) * 0.5f,
            )
            val perpendicular = abs((midpoint.x - origin.x) * -uz + (midpoint.z - origin.z) * ux)
            if (perpendicular > MAX_MERGE_LINE_DISTANCE_METERS) return false

            val p0 = project(candidate.start)
            val p1 = project(candidate.end)
            val candidateFrom = min(p0, p1)
            val candidateTo = max(p0, p1)
            val gap = when {
                candidateTo < from -> from - candidateTo
                candidateFrom > to -> candidateFrom - to
                else -> 0f
            }
            return gap <= MAX_MERGE_GAP_METERS
        }

        fun merge(candidate: WallSegment) {
            val p0 = project(candidate.start)
            val p1 = project(candidate.end)
            from = min(from, min(p0, p1))
            to = max(to, max(p0, p1))
            val candidateWeight = distance(candidate.start, candidate.end).coerceAtLeast(0.01f)
            thickness = (thickness * weight + candidate.thicknessMeters * candidateWeight) / (weight + candidateWeight)
            weight += candidateWeight
            confidence = max(confidence, candidate.confidence)
        }

        fun toWall(): WallSegment = WallSegment(
            start = Vec2(origin.x + ux * from, origin.z + uz * from),
            end = Vec2(origin.x + ux * to, origin.z + uz * to),
            thicknessMeters = thickness,
            confidence = confidence,
        )

        private fun project(point: Vec2): Float =
            (point.x - origin.x) * ux + (point.z - origin.z) * uz
    }

    private fun nearlySameWall(a: WallSegment, b: WallSegment): Boolean {
        val al = distance(a.start, a.end)
        val bl = distance(b.start, b.end)
        if (al <= 0.0001f || bl <= 0.0001f) return false
        val aux = (a.end.x - a.start.x) / al
        val auz = (a.end.z - a.start.z) / al
        val bux = (b.end.x - b.start.x) / bl
        val buz = (b.end.z - b.start.z) / bl
        if (abs(aux * bux + auz * buz) < COS_DUPLICATE_ANGLE) return false
        val midpoint = Vec2((a.start.x + a.end.x) * 0.5f, (a.start.z + a.end.z) * 0.5f)
        return pointSegmentDistance(midpoint, b.start, b.end) <= max(a.thicknessMeters, b.thicknessMeters) * 0.75f + 0.08f
    }

    private fun planToPixel(point: Vec2, bounds: PixelContentBounds, pxToMeter: Float): Pair<Float, Float> =
        Pair(
            (bounds.left + bounds.rightExclusive) * 0.5f + point.x / pxToMeter,
            (bounds.top + bounds.bottomExclusive) * 0.5f + point.z / pxToMeter,
        )

    private fun pixelToPlan(point: PixelPoint, bounds: PixelContentBounds, pxToMeter: Float): Vec2 =
        Vec2(
            x = (point.x - (bounds.left + bounds.rightExclusive) * 0.5f) * pxToMeter,
            z = (point.y - (bounds.top + bounds.bottomExclusive) * 0.5f) * pxToMeter,
        )

    private fun pointSegmentDistance(point: Vec2, a: Vec2, b: Vec2): Float {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= 0.000001f) return distance(point, a)
        val t = (((point.x - a.x) * vx + (point.z - a.z) * vz) / lengthSq).coerceIn(0f, 1f)
        val q = Vec2(a.x + vx * t, a.z + vz * t)
        return distance(point, q)
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun normalizeHalfTurnDegrees(value: Float): Float {
        var angle = value % 180f
        if (angle < 0f) angle += 180f
        return angle
    }

    private data class PixelPoint(val x: Float, val y: Float)

    private val THICKNESS_SAMPLE_POSITIONS = floatArrayOf(0.14f, 0.30f, 0.50f, 0.70f, 0.86f)

    private const val MIN_THICKNESS_CONTEXT_PX = 18f
    private const val MAX_THICKNESS_HALF_PX = 28
    private const val ALONG_SAMPLE_RADIUS = 2
    private const val MIN_PROFILE_SUPPORT = 2
    private const val MIN_PROFILE_DARK_OFFSETS = 2
    private const val MIN_THICKNESS_SPAN_PX = 2f
    private const val MAX_THICKNESS_SPAN_PX = 48f
    private const val MAX_SINGLE_STROKE_SPAN_PX = 6f
    private const val MIN_OPPOSITE_FACE_OFFSET_PX = 2
    private const val MIN_THICKNESS_SAMPLES = 3
    private const val MIN_MEASURED_THICKNESS_METERS = 0.09f
    private const val MAX_MEASURED_THICKNESS_METERS = 0.42f

    private const val MIN_TILE_PX = 36
    private const val MAX_TILE_PX = 76
    private const val TILE_DIVISOR = 22
    private const val MIN_TILE_STEP_PX = 18
    private const val PCA_PIXEL_STEP = 2
    private const val MIN_PCA_POINTS = 24
    private const val MIN_PCA_ANISOTROPY = 5.8
    private const val MIN_AXIS_ANGLE_DISTANCE_DEGREES = 7.5f
    private const val PROJECTION_LOW_QUANTILE = 0.08f
    private const val PROJECTION_HIGH_QUANTILE = 0.92f
    private const val MIN_TILE_SPAN_RATIO = 0.58f
    private const val MIN_DIAGONAL_CANDIDATE_METERS = 0.70f
    private const val MIN_DIAGONAL_THICKNESS_PX = 2f
    private const val MAX_DIAGONAL_THICKNESS_PX = 30f
    private const val MIN_DIAGONAL_SUPPORT = 0.82f
    private const val MIN_SUPPORT_SAMPLES = 10
    private const val SUPPORT_SAMPLE_SPACING_PX = 3.5f
    private const val MIN_OUTPUT_CONFIDENCE = 0.76f
    private const val MIN_DIAGONAL_OUTPUT_METERS = 0.90f
    private const val LONG_STANDALONE_DIAGONAL_METERS = 1.75f
    private const val CONNECTION_DISTANCE_METERS = 0.34f
    private const val MAX_MERGE_LINE_DISTANCE_METERS = 0.18f
    private const val MAX_MERGE_GAP_METERS = 0.32f
    private val COS_MAX_MERGE_ANGLE = cos(5.0 * PI / 180.0).toFloat()
    private val COS_DUPLICATE_ANGLE = cos(7.0 * PI / 180.0).toFloat()
}
