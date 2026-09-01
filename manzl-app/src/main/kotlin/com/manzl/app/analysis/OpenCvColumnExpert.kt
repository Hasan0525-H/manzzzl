package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Independent deterministic compact-structure expert for architectural columns.
 *
 * It searches the same source structural raster for compact near-rectangular contours, converts the
 * oriented rectangle to metric coordinates, and verifies ink support along all four measured faces.
 * This path does not need the distilled student, so a clean CAD/scan can still recover columns when
 * neural weights are unavailable. It intentionally misses ambiguous connected shapes rather than
 * interpreting text or wall intersections as columns.
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
                val contour2f = MatOfPoint2f(*points)
                val rect = try {
                    Imgproc.minAreaRect(contour2f)
                } finally {
                    contour2f.release()
                }

                val rawWidth = abs(rect.size.width).toFloat()
                val rawHeight = abs(rect.size.height).toFloat()
                if (rawWidth < MIN_RECT_PIXELS || rawHeight < MIN_RECT_PIXELS) continue
                val rectAreaPx = rawWidth * rawHeight
                if (rectAreaPx <= 1f) continue
                val contourAreaPx = abs(Imgproc.contourArea(contour)).toFloat()
                val rectangularity = (contourAreaPx / rectAreaPx).coerceIn(0f, 1f)
                if (rectangularity < MIN_RECTANGULARITY) continue

                val majorPx: Float
                val minorPx: Float
                var rotation = rect.angle.toFloat()
                if (rawWidth >= rawHeight) {
                    majorPx = rawWidth
                    minorPx = rawHeight
                } else {
                    majorPx = rawHeight
                    minorPx = rawWidth
                    rotation += 90f
                }
                val majorMeters = majorPx / pixelsPerMeter
                val minorMeters = minorPx / pixelsPerMeter
                if (majorMeters !in MIN_COLUMN_METERS..MAX_COLUMN_METERS) continue
                if (minorMeters !in MIN_COLUMN_METERS..MAX_COLUMN_METERS) continue
                if (majorMeters / minorMeters.coerceAtLeast(0.001f) > MAX_ASPECT_RATIO) continue
                val areaMeters = majorMeters * minorMeters
                if (areaMeters !in MIN_AREA_SQ_METERS..MAX_AREA_SQ_METERS) continue

                val rectPoints = Array(4) { Point() }
                rect.points(rectPoints)
                val borderSupport = rectangleBorderSupport(
                    mask = structural.mask,
                    width = working.width,
                    height = working.height,
                    corners = rectPoints,
                )
                if (borderSupport < MIN_BORDER_SUPPORT) continue
                proposed++

                val center = transform.imageToPlan(rect.center.x.toFloat(), rect.center.y.toFloat())
                val confidence = (
                    BASE_CONFIDENCE +
                        rectangularity * RECTANGULARITY_WEIGHT +
                        borderSupport * BORDER_WEIGHT
                    ).coerceIn(MIN_ACCEPTED_CONFIDENCE, MAX_ACCEPTED_CONFIDENCE)
                val candidate = StructuralColumn(
                    center = center,
                    widthMeters = majorMeters,
                    depthMeters = minorMeters,
                    rotationDegrees = normalizeHalfTurn(rotation),
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
