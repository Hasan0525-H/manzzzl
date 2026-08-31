package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV expert that reasons from *paired wall faces* instead of a single Hough stroke.
 *
 * Floor-plan walls are usually drawn as two parallel boundaries or as a thick filled band. Treating
 * one edge as the wall centre can shift a room by half a wall thickness and may create duplicate
 * walls. This expert pairs compatible edge runs, derives a physical centreline and thickness, then
 * asks the independent raster fidelity evaluator whether replacing/adding that measured wall actually
 * improves reconstruction. It has no authority to force a candidate into the house.
 */
internal object OpenCvWallFaceExpert {

    data class Result(
        val plan: FloorPlan,
        val proposedCount: Int,
        val acceptedCount: Int,
        val replacedCount: Int,
        val addedCount: Int,
        val runtimeAvailable: Boolean,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        if (seed.walls.size < 4 || source.width <= 32 || source.height <= 32) {
            return Result(seed, 0, 0, 0, 0, false)
        }
        if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
            return Result(seed, 0, 0, 0, 0, false)
        }

        val working = source.downscale(MAX_ANALYSIS_SIDE)
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val lines = Mat()

        return try {
            Utils.bitmapToMat(working, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH, 3, true)

            val transform = PlanRasterTransform.forImage(seed, working.width, working.height)
            val pixelsPerMeter = ((transform.pixelsPerMeterX + transform.pixelsPerMeterZ) * 0.5f)
                .coerceAtLeast(1f)
            val minLinePixels = max(MIN_HOUGH_LINE_PIXELS.toFloat(), MIN_HOUGH_LINE_METERS * pixelsPerMeter)
            val maxGapPixels = (MAX_HOUGH_GAP_METERS * pixelsPerMeter)
                .coerceIn(2f, MAX_HOUGH_GAP_PIXELS.toFloat())

            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,
                Math.PI / 720.0,
                HOUGH_THRESHOLD,
                minLinePixels.toDouble(),
                maxGapPixels.toDouble(),
            )

            val edgeLines = readLines(lines)
            val paired = WallFacePairing.pair(
                lines = edgeLines,
                minThicknessPx = MIN_WALL_THICKNESS_METERS * pixelsPerMeter,
                maxThicknessPx = MAX_WALL_THICKNESS_METERS * pixelsPerMeter,
                minOverlapPx = max(MIN_FACE_OVERLAP_PIXELS, MIN_FACE_OVERLAP_METERS * pixelsPerMeter),
                maxAngleErrorDegrees = MAX_FACE_ANGLE_ERROR_DEGREES,
                minOverlapRatio = MIN_FACE_OVERLAP_RATIO,
                maxResults = MAX_FACE_PAIR_CANDIDATES,
            )
            val candidates = paired.mapNotNull { candidate ->
                val start = transform.imageToPlan(candidate.x0, candidate.y0)
                val end = transform.imageToPlan(candidate.x1, candidate.y1)
                val length = distance(start, end)
                if (length < MIN_OUTPUT_WALL_METERS) return@mapNotNull null
                WallSegment(
                    start = start,
                    end = end,
                    thicknessMeters = (candidate.thicknessPx / pixelsPerMeter)
                        .coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS),
                    confidence = candidate.confidence.coerceIn(MIN_FACE_CONFIDENCE, MAX_FACE_CONFIDENCE),
                )
            }

            val structuralMask = StructuralRasterMask.classify(working).mask
            var current = seed
            var currentReport = GeometryFidelityEvaluator.evaluate(
                structuralMask = structuralMask,
                imageWidth = working.width,
                imageHeight = working.height,
                plan = current,
            )
            var accepted = 0
            var replaced = 0
            var added = 0

            for (candidate in candidates) {
                val replacementIndex = bestReplacementIndex(candidate, current.walls)
                val trialWalls = if (replacementIndex >= 0) {
                    current.walls.toMutableList().also { it[replacementIndex] = candidate }
                } else {
                    if (!connectsToMeasuredNetwork(candidate, current.walls)) continue
                    if (current.walls.any { nearlySameWall(it, candidate) }) continue
                    current.walls + candidate
                }

                val trial = current.copy(walls = trialWalls)
                val report = GeometryFidelityEvaluator.evaluate(
                    structuralMask = structuralMask,
                    imageWidth = working.width,
                    imageHeight = working.height,
                    plan = trial,
                )
                val scoreGain = report.score - currentReport.score
                val coverageGain = report.wallCoverage - currentReport.wallCoverage
                val precisionGain = report.wallPrecision - currentReport.wallPrecision
                val endpointLoss = currentReport.endpointSupport - report.endpointSupport

                val acceptReplacement = replacementIndex >= 0 &&
                    scoreGain >= MIN_REPLACEMENT_SCORE_GAIN &&
                    coverageGain >= -MAX_REPLACEMENT_COVERAGE_LOSS &&
                    precisionGain >= MIN_REPLACEMENT_PRECISION_GAIN &&
                    endpointLoss <= MAX_ENDPOINT_SUPPORT_LOSS

                val acceptAddition = replacementIndex < 0 &&
                    coverageGain >= MIN_ADDITION_COVERAGE_GAIN &&
                    precisionGain >= -MAX_ADDITION_PRECISION_LOSS &&
                    scoreGain >= -MAX_ADDITION_SCORE_LOSS &&
                    endpointLoss <= MAX_ENDPOINT_SUPPORT_LOSS

                if (acceptReplacement || acceptAddition) {
                    current = trial.copy(geometryFidelity = report)
                    currentReport = report
                    accepted++
                    if (replacementIndex >= 0) replaced++ else added++
                }
            }

            Result(
                plan = if (accepted == 0) seed else current.copy(geometryFidelity = currentReport),
                proposedCount = candidates.size,
                acceptedCount = accepted,
                replacedCount = replaced,
                addedCount = added,
                runtimeAvailable = true,
            )
        } catch (_: RuntimeException) {
            Result(seed, 0, 0, 0, 0, true)
        } finally {
            rgba.release()
            gray.release()
            blurred.release()
            edges.release()
            lines.release()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun readLines(lines: Mat): List<WallFacePairing.PixelLine> {
        if (lines.empty()) return emptyList()
        val result = ArrayList<WallFacePairing.PixelLine>()
        for (row in 0 until lines.rows()) {
            val values = lines.get(row, 0) ?: continue
            if (values.size < 4) continue
            result += WallFacePairing.PixelLine(
                x0 = values[0].toFloat(),
                y0 = values[1].toFloat(),
                x1 = values[2].toFloat(),
                y1 = values[3].toFloat(),
            )
        }
        return result
    }

    private fun bestReplacementIndex(candidate: WallSegment, walls: List<WallSegment>): Int {
        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY
        walls.forEachIndexed { index, existing ->
            val alignment = axisAlignment(existing, candidate)
            if (alignment < MIN_REPLACEMENT_ALIGNMENT) return@forEachIndexed
            val midpointDistance = pointSegmentDistance(midpoint(candidate), existing.start, existing.end)
            if (midpointDistance > MAX_REPLACEMENT_CENTER_DISTANCE_METERS) return@forEachIndexed
            val overlap = projectedOverlapRatio(existing, candidate)
            if (overlap < MIN_REPLACEMENT_OVERLAP_RATIO) return@forEachIndexed
            val score = alignment * 0.45f + overlap * 0.35f +
                (1f - midpointDistance / MAX_REPLACEMENT_CENTER_DISTANCE_METERS).coerceIn(0f, 1f) * 0.20f
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun projectedOverlapRatio(a: WallSegment, b: WallSegment): Float {
        val al = distance(a.start, a.end)
        if (al <= EPSILON) return 0f
        val ux = (a.end.x - a.start.x) / al
        val uz = (a.end.z - a.start.z) / al
        fun projection(point: Vec2): Float = point.x * ux + point.z * uz
        val a0 = projection(a.start)
        val a1 = projection(a.end)
        val b0 = projection(b.start)
        val b1 = projection(b.end)
        val amin = min(a0, a1)
        val amax = max(a0, a1)
        val bmin = min(b0, b1)
        val bmax = max(b0, b1)
        val overlap = (min(amax, bmax) - max(amin, bmin)).coerceAtLeast(0f)
        val shorter = min(amax - amin, bmax - bmin).coerceAtLeast(EPSILON)
        return (overlap / shorter).coerceIn(0f, 1f)
    }

    private fun connectsToMeasuredNetwork(candidate: WallSegment, measured: List<WallSegment>): Boolean =
        measured.any { pointSegmentDistance(candidate.start, it.start, it.end) <= CONNECTION_METERS } ||
            measured.any { pointSegmentDistance(candidate.end, it.start, it.end) <= CONNECTION_METERS } ||
            measured.any { segmentsIntersect(candidate.start, candidate.end, it.start, it.end) }

    private fun nearlySameWall(a: WallSegment, b: WallSegment): Boolean =
        axisAlignment(a, b) >= MIN_DUPLICATE_ALIGNMENT &&
            pointSegmentDistance(midpoint(b), a.start, a.end) <= DUPLICATE_LINE_DISTANCE_METERS &&
            projectedOverlapRatio(a, b) >= MIN_DUPLICATE_OVERLAP_RATIO

    private fun axisAlignment(a: WallSegment, b: WallSegment): Float {
        val al = distance(a.start, a.end)
        val bl = distance(b.start, b.end)
        if (al <= EPSILON || bl <= EPSILON) return 0f
        val aux = (a.end.x - a.start.x) / al
        val auz = (a.end.z - a.start.z) / al
        val bux = (b.end.x - b.start.x) / bl
        val buz = (b.end.z - b.start.z) / bl
        return abs(aux * bux + auz * buz)
    }

    private fun midpoint(wall: WallSegment) = Vec2(
        (wall.start.x + wall.end.x) * 0.5f,
        (wall.start.z + wall.end.z) * 0.5f,
    )

    private fun pointSegmentDistance(point: Vec2, a: Vec2, b: Vec2): Float {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= EPSILON) return distance(point, a)
        val t = (((point.x - a.x) * vx + (point.z - a.z) * vz) / lengthSq).coerceIn(0f, 1f)
        return distance(point, Vec2(a.x + vx * t, a.z + vz * t))
    }

    private fun segmentsIntersect(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Boolean {
        val arx = a1.x - a0.x
        val arz = a1.z - a0.z
        val brx = b1.x - b0.x
        val brz = b1.z - b0.z
        val denominator = arx * brz - arz * brx
        if (abs(denominator) <= EPSILON) return false
        val qx = b0.x - a0.x
        val qz = b0.z - a0.z
        val t = (qx * brz - qz * brx) / denominator
        val u = (qx * arz - qz * arx) / denominator
        return t in 0f..1f && u in 0f..1f
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dz = b.z - a.z
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
    private const val CANNY_LOW = 38.0
    private const val CANNY_HIGH = 122.0
    private const val HOUGH_THRESHOLD = 30
    private const val MIN_HOUGH_LINE_METERS = 0.38f
    private const val MIN_HOUGH_LINE_PIXELS = 20
    private const val MAX_HOUGH_GAP_METERS = 0.08f
    private const val MAX_HOUGH_GAP_PIXELS = 9
    private const val MIN_WALL_THICKNESS_METERS = 0.075f
    private const val MAX_WALL_THICKNESS_METERS = 0.48f
    private const val MIN_FACE_OVERLAP_METERS = 0.52f
    private const val MIN_FACE_OVERLAP_PIXELS = 30f
    private const val MIN_FACE_OVERLAP_RATIO = 0.52f
    private const val MAX_FACE_ANGLE_ERROR_DEGREES = 3.0f
    private const val MAX_FACE_PAIR_CANDIDATES = 96
    private const val MIN_OUTPUT_WALL_METERS = 0.48f
    private const val MIN_FACE_CONFIDENCE = 0.74f
    private const val MAX_FACE_CONFIDENCE = 0.97f

    private const val MIN_REPLACEMENT_ALIGNMENT = 0.992f
    private const val MAX_REPLACEMENT_CENTER_DISTANCE_METERS = 0.30f
    private const val MIN_REPLACEMENT_OVERLAP_RATIO = 0.54f
    private const val MIN_REPLACEMENT_SCORE_GAIN = 0.0015f
    private const val MAX_REPLACEMENT_COVERAGE_LOSS = 0.004f
    private const val MIN_REPLACEMENT_PRECISION_GAIN = 0.001f

    private const val CONNECTION_METERS = 0.34f
    private const val MIN_ADDITION_COVERAGE_GAIN = 0.0030f
    private const val MAX_ADDITION_PRECISION_LOSS = 0.007f
    private const val MAX_ADDITION_SCORE_LOSS = 0.0015f
    private const val MAX_ENDPOINT_SUPPORT_LOSS = 0.025f

    private const val MIN_DUPLICATE_ALIGNMENT = 0.991f
    private const val DUPLICATE_LINE_DISTANCE_METERS = 0.12f
    private const val MIN_DUPLICATE_OVERLAP_RATIO = 0.60f
    private const val EPSILON = 0.000001f
}
