package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Independent deterministic compact-structure expert for architectural columns.
 *
 * The OpenCV Android Maven binding has changed some generated RotatedRect helpers across releases.
 * To keep this expert portable, OpenCV is used only for contour extraction; oriented fitting,
 * rectangularity and face support are computed here deterministically from the contour points.
 * This also makes the geometry easy to unit-test without depending on JNI-specific helper methods.
 */
internal object OpenCvColumnExpert {

    data class Result(
        val plan: FloorPlan,
        val proposedCount: Int,
        val acceptedCount: Int,
        val runtimeAvailable: Boolean,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        if (source.width <= 32 || source.height <= 32) return Result(seed, 0, 0, false)
        if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
            return Result(seed, 0, 0, false)
        }

        val working = source.downscale(MAX_ANALYSIS_SIDE)
        val binary = Mat(working.height, working.width, CvType.CV_8UC1)
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        return try {
            val structural = StructuralRasterMask.classify(working)
            val bytes = ByteArray(structural.mask.size) { index ->
                if (structural.mask[index]) 0xFF.toByte() else 0
            }
            binary.put(0, 0, bytes)
            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val transform = PlanRasterTransform.forImage(seed, working.width, working.height)
            val pixelsPerMeter = ((transform.pixelsPerMeterX + transform.pixelsPerMeterZ) * 0.5f)
                .coerceAtLeast(1f)
            val candidates = ArrayList<StructuralColumn>()
            var proposed = 0

            for (contour in contours) {
                if (contour.rows() < MIN_CONTOUR_POINTS) continue
                val points = contour.toArray()
                val fitted = orientedBounds(points) ?: continue
                val rawWidth = fitted.majorSpan
                val rawHeight = fitted.minorSpan
                if (rawWidth < MIN_RECT_PIXELS || rawHeight < MIN_RECT_PIXELS) continue

                val rectAreaPx = rawWidth * rawHeight
                if (rectAreaPx <= 1f) continue
                val contourAreaPx = polygonArea(points)
                val rectangularity = (contourAreaPx / rectAreaPx).coerceIn(0f, 1f)
                if (rectangularity < MIN_RECTANGULARITY) continue

                val majorMeters = rawWidth / pixelsPerMeter
                val minorMeters = rawHeight / pixelsPerMeter
                if (majorMeters !in MIN_COLUMN_METERS..MAX_COLUMN_METERS) continue
                if (minorMeters !in MIN_COLUMN_METERS..MAX_COLUMN_METERS) continue
                if (majorMeters / minorMeters.coerceAtLeast(0.001f) > MAX_ASPECT_RATIO) continue
                val areaMeters = majorMeters * minorMeters
                if (areaMeters !in MIN_AREA_SQ_METERS..MAX_AREA_SQ_METERS) continue

                val borderSupport = rectangleBorderSupport(
                    mask = structural.mask,
                    width = working.width,
                    height = working.height,
                    corners = fitted.corners,
                )
                if (borderSupport < MIN_BORDER_SUPPORT) continue
                proposed++

                val center = transform.imageToPlan(fitted.center.x.toFloat(), fitted.center.y.toFloat())
                val confidence = (
                    BASE_CONFIDENCE +
                        rectangularity * RECTANGULARITY_WEIGHT +
                        borderSupport * BORDER_WEIGHT
                    ).coerceIn(MIN_ACCEPTED_CONFIDENCE, MAX_ACCEPTED_CONFIDENCE)
                val candidate = StructuralColumn(
                    center = center,
                    widthMeters = majorMeters,
                    depthMeters = minorMeters,
                    rotationDegrees = normalizeHalfTurn(fitted.rotationDegrees),
                    confidence = confidence,
                )
                if (seed.columns.any { existing -> duplicate(existing, candidate) }) continue
                if (candidates.any { existing -> duplicate(existing, candidate) }) continue
                candidates += candidate
                if (candidates.size >= MAX_COLUMNS_PER_FLOOR) break
            }

            val merged = if (candidates.isEmpty()) seed.columns else seed.columns + candidates
            Result(
                plan = if (candidates.isEmpty()) seed else seed.copy(columns = merged),
                proposedCount = proposed,
                acceptedCount = candidates.size,
                runtimeAvailable = true,
            )
        } catch (_: RuntimeException) {
            Result(seed, 0, 0, true)
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            binary.release()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    /** PCA fit followed by exact min/max projection gives a stable oriented box for compact contours. */
    internal fun orientedBounds(points: Array<Point>): OrientedBounds? {
        if (points.size < MIN_CONTOUR_POINTS) return null
        val meanX = points.sumOf { it.x } / points.size
        val meanY = points.sumOf { it.y } / points.size
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (point in points) {
            val dx = point.x - meanX
            val dy = point.y - meanY
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        xx /= points.size
        yy /= points.size
        xy /= points.size
        if (xx + yy <= 1e-8) return null

        val angle = 0.5 * atan2(2.0 * xy, xx - yy)
        var ux = cos(angle)
        var uy = sin(angle)
        var nx = -uy
        var ny = ux

        fun projection(point: Point, ax: Double, ay: Double): Double = point.x * ax + point.y * ay
        var minU = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
        for (point in points) {
            val u = projection(point, ux, uy)
            val n = projection(point, nx, ny)
            minU = min(minU, u)
            maxU = max(maxU, u)
            minN = min(minN, n)
            maxN = max(maxN, n)
        }
        var spanU = maxU - minU
        var spanN = maxN - minN
        if (spanU <= 0.0 || spanN <= 0.0) return null

        // StructuralColumn.width follows the reported major axis. Rotate the basis by 90° when PCA
        // happens to return the smaller span as its first vector (possible for nearly square shapes).
        if (spanN > spanU) {
            val oldUx = ux
            val oldUy = uy
            ux = nx
            uy = ny
            nx = -oldUx
            ny = -oldUy
            val oldMinU = minU
            val oldMaxU = maxU
            minU = minN
            maxU = maxN
            minN = oldMinU
            maxN = oldMaxU
            spanU = maxU - minU
            spanN = maxN - minN
        }

        val centerU = (minU + maxU) * 0.5
        val centerN = (minN + maxN) * 0.5
        val center = Point(
            ux * centerU + nx * centerN,
            uy * centerU + ny * centerN,
        )
        fun corner(u: Double, n: Double) = Point(
            ux * u + nx * n,
            uy * u + ny * n,
        )
        val corners = arrayOf(
            corner(minU, minN),
            corner(maxU, minN),
            corner(maxU, maxN),
            corner(minU, maxN),
        )
        return OrientedBounds(
            center = center,
            majorSpan = spanU.toFloat(),
            minorSpan = spanN.toFloat(),
            rotationDegrees = Math.toDegrees(atan2(uy, ux)).toFloat(),
            corners = corners,
        )
    }

    internal fun polygonArea(points: Array<Point>): Float {
        if (points.size < 3) return 0f
        var twiceArea = 0.0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            twiceArea += a.x * b.y - b.x * a.y
        }
        return (abs(twiceArea) * 0.5).toFloat()
    }

    private fun rectangleBorderSupport(
        mask: BooleanArray,
        width: Int,
        height: Int,
        corners: Array<Point>,
    ): Float {
        if (corners.size != 4 || mask.size != width * height) return 0f
        var supported = 0
        var total = 0
        for (edge in 0 until 4) {
            val a = corners[edge]
            val b = corners[(edge + 1) % 4]
            for (sample in 0 until BORDER_SAMPLES_PER_EDGE) {
                val t = if (BORDER_SAMPLES_PER_EDGE <= 1) 0.5 else sample / (BORDER_SAMPLES_PER_EDGE - 1.0)
                val x = (a.x + (b.x - a.x) * t).toInt()
                val y = (a.y + (b.y - a.y) * t).toInt()
                total++
                if (hasInkNear(mask, width, height, x, y, BORDER_SEARCH_RADIUS_PX)) supported++
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
        val radiusSq = radius * radius
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y !in 0 until height) continue
            val row = y * width
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radiusSq) continue
                val x = cx + dx
                if (x in 0 until width && mask[row + x]) return true
            }
        }
        return false
    }

    private fun duplicate(a: StructuralColumn, b: StructuralColumn): Boolean {
        val distance = distance(a.center, b.center)
        if (distance > DUPLICATE_CENTER_METERS) return false
        val aArea = a.widthMeters * a.depthMeters
        val bArea = b.widthMeters * b.depthMeters
        val areaRatio = min(aArea, bArea) / max(aArea, bArea).coerceAtLeast(0.001f)
        return areaRatio >= DUPLICATE_AREA_RATIO
    }

    private fun normalizeHalfTurn(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun Bitmap.downscale(maxSide: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            max(1, (width * ratio).toInt()),
            max(1, (height * ratio).toInt()),
            true,
        )
    }

    internal data class OrientedBounds(
        val center: Point,
        val majorSpan: Float,
        val minorSpan: Float,
        val rotationDegrees: Float,
        val corners: Array<Point>,
    )

    private const val MAX_ANALYSIS_SIDE = 2800
    private const val MIN_CONTOUR_POINTS = 4
    private const val MIN_RECT_PIXELS = 5f
    private const val MIN_COLUMN_METERS = 0.16f
    private const val MAX_COLUMN_METERS = 1.40f
    private const val MIN_AREA_SQ_METERS = 0.035f
    private const val MAX_AREA_SQ_METERS = 1.60f
    private const val MAX_ASPECT_RATIO = 2.8f
    private const val MIN_RECTANGULARITY = 0.56f
    private const val BORDER_SAMPLES_PER_EDGE = 13
    private const val BORDER_SEARCH_RADIUS_PX = 2
    private const val MIN_BORDER_SUPPORT = 0.72f
    private const val BASE_CONFIDENCE = 0.57f
    private const val RECTANGULARITY_WEIGHT = 0.18f
    private const val BORDER_WEIGHT = 0.22f
    private const val MIN_ACCEPTED_CONFIDENCE = 0.78f
    private const val MAX_ACCEPTED_CONFIDENCE = 0.97f
    private const val MAX_COLUMNS_PER_FLOOR = 64
    private const val DUPLICATE_CENTER_METERS = 0.24f
    private const val DUPLICATE_AREA_RATIO = 0.60f
}