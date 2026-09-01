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
 * Independent OpenCV expert for the ultra 2D→house reconstruction path.
 *
 * The first pass pairs opposite raster wall faces to recover a physical wall centreline and measured
 * thickness. Only after that does the older single-stroke Hough path look for still-missing walls.
 * Both passes remain proposal-only: every accepted change must improve independent source-raster
 * fidelity, so OpenCV never becomes geometry authority.
 *
 * Both sub-experts are mandatory in Ultra mode. Zero safe proposals is a legitimate result; a runtime
 * failure is not. The returned [runtimeAvailable] therefore requires both passes to execute normally.
 */
internal object OpenCvWallExpert {

    data class Result(
        val plan: FloorPlan,
        val proposedCount: Int,
        val acceptedCount: Int,
        val runtimeAvailable: Boolean,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        val faceResult = OpenCvWallFaceExpert.refine(source, seed)
        if (!faceResult.runtimeAvailable) {
            return Result(
                plan = seed,
                proposedCount = faceResult.proposedCount,
                acceptedCount = 0,
                runtimeAvailable = false,
            )
        }

        val strokeResult = refineStrokeLines(source, faceResult.plan)
        return Result(
            plan = if (strokeResult.runtimeAvailable) strokeResult.plan else faceResult.plan,
            proposedCount = faceResult.proposedCount + strokeResult.proposedCount,
            acceptedCount = faceResult.acceptedCount + strokeResult.acceptedCount,
            runtimeAvailable = faceResult.runtimeAvailable && strokeResult.runtimeAvailable,
        )
    }

    private fun refineStrokeLines(source: Bitmap, seed: FloorPlan): Result {
        if (seed.walls.size < 4 || source.width <= 32 || source.height <= 32) {
            return Result(seed, 0, 0, false)
        }
        if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
            return Result(seed, 0, 0, false)
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
            val minLinePixels = (
                MIN_HOUGH_LINE_METERS * min(transform.pixelsPerMeterX, transform.pixelsPerMeterZ)
                ).coerceAtLeast(MIN_HOUGH_LINE_PIXELS.toFloat())
            val maxGapPixels = (
                MAX_HOUGH_GAP_METERS * min(transform.pixelsPerMeterX, transform.pixelsPerMeterZ)
                ).coerceIn(2f, MAX_HOUGH_GAP_PIXELS.toFloat())

            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,
                Math.PI / 720.0,
                HOUGH_THRESHOLD,
                minLinePixels.toDouble(),
                maxGapPixels.toDouble(),
            )

            val candidates = readCandidates(lines, seed, transform)
            val structuralMask = StructuralRasterMask.classify(working).mask
            var current = seed
            var currentReport = GeometryFidelityEvaluator.evaluate(
                structuralMask = structuralMask,
                imageWidth = working.width,
                imageHeight = working.height,
                plan = current,
            )
            var accepted = 0

            for (candidate in candidates.take(MAX_CANDIDATES_TO_ADJUDICATE)) {
                if (current.walls.any { existing -> nearlySameWall(existing, candidate) }) continue
                if (!connectsToMeasuredNetwork(candidate, current.walls)) continue

                val trial = current.copy(walls = current.walls + candidate)
                val report = GeometryFidelityEvaluator.evaluate(
                    structuralMask = structuralMask,
                    imageWidth = working.width,
                    imageHeight = working.height,
                    plan = trial,
                )
                val coverageGain = report.wallCoverage - currentReport.wallCoverage
                val precisionLoss = currentReport.wallPrecision - report.wallPrecision
                val scoreLoss = currentReport.score - report.score
                val endpointLoss = currentReport.endpointSupport - report.endpointSupport

                if (
                    coverageGain >= MIN_COVERAGE_GAIN &&
                    precisionLoss <= MAX_PRECISION_LOSS &&
                    scoreLoss <= MAX_SCORE_LOSS &&
                    endpointLoss <= MAX_ENDPOINT_SUPPORT_LOSS
                ) {
                    current = trial.copy(geometryFidelity = report)
                    currentReport = report
                    accepted++
                }
            }

            if (accepted == 0) {
                Result(seed, candidates.size, 0, true)
            } else {
                Result(
                    plan = current.copy(geometryFidelity = currentReport),
                    proposedCount = candidates.size,
                    acceptedCount = accepted,
                    runtimeAvailable = true,
                )
            }
        } catch (_: RuntimeException) {
            Result(seed, 0, 0, false)
        } finally {
            rgba.release()
            gray.release()
            blurred.release()
            edges.release()
            lines.release()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun readCandidates(
        lines: Mat,
        seed: FloorPlan,
        transform: PlanRasterTransform,
    ): List<WallSegment> {
        if (lines.empty()) return emptyList()
        val medianThickness = seed.walls.map { it.thicknessMeters }
            .sorted()
            .let { values -> values.getOrNull(values.size / 2) ?: DEFAULT_WALL_THICKNESS_METERS }
            .coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS)

        val result = ArrayList<WallSegment>()
        for (row in 0 until lines.rows()) {
            val values = lines.get(row, 0) ?: continue
            if (values.size < 4) continue
            val start = transform.imageToPlan(values[0].toFloat(), values[1].toFloat())
            val end = transform.imageToPlan(values[2].toFloat(), values[3].toFloat())
            val length = distance(start, end)
            if (length < MIN_OUTPUT_WALL_METERS) continue
            result += WallSegment(
                start = start,
                end = end,
                thicknessMeters = medianThickness,
                confidence = (BASE_HOUGH_CONFIDENCE + (length / LONG_LINE_METERS) * LENGTH_CONFIDENCE_GAIN)
                    .coerceIn(BASE_HOUGH_CONFIDENCE, MAX_HOUGH_CONFIDENCE),
            )
        }

        return result
            .sortedByDescending { distance(it.start, it.end) }
            .fold(ArrayList<WallSegment>()) { accepted, candidate ->
                if (accepted.none { existing -> nearlySameWall(existing, candidate) }) accepted += candidate
                accepted
            }
    }

    private fun connectsToMeasuredNetwork(candidate: WallSegment, measured: List<WallSegment>): Boolean {
        var endpointConnections = 0
        if (measured.any { wall -> pointSegmentDistance(candidate.start, wall.start, wall.end) <= CONNECTION_METERS }) {
            endpointConnections++
        }
        if (measured.any { wall -> pointSegmentDistance(candidate.end, wall.start, wall.end) <= CONNECTION_METERS }) {
            endpointConnections++
        }
        if (endpointConnections > 0) return true

        // A wall crossing a trusted measured wall can still be a valid missing partition even when
        // the detected Hough segment ends are slightly short/long.
        return measured.any { wall -> segmentsIntersect(candidate.start, candidate.end, wall.start, wall.end) }
    }

    private fun nearlySameWall(a: WallSegment, b: WallSegment): Boolean {
        val aLength = distance(a.start, a.end)
        val bLength = distance(b.start, b.end)
        if (aLength <= EPSILON || bLength <= EPSILON) return false
        val aux = (a.end.x - a.start.x) / aLength
        val auz = (a.end.z - a.start.z) / aLength
        val bux = (b.end.x - b.start.x) / bLength
        val buz = (b.end.z - b.start.z) / bLength
        val alignment = abs(aux * bux + auz * buz)
        if (alignment < MIN_DUPLICATE_ALIGNMENT) return false
        val midpoint = Vec2((b.start.x + b.end.x) * 0.5f, (b.start.z + b.end.z) * 0.5f)
        return pointSegmentDistance(midpoint, a.start, a.end) <= DUPLICATE_LINE_DISTANCE_METERS
    }

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

    private const val MAX_ANALYSIS_SIDE = 2600
    private const val CANNY_LOW = 42.0
    private const val CANNY_HIGH = 128.0
    private const val HOUGH_THRESHOLD = 34
    private const val MIN_HOUGH_LINE_METERS = 0.42f
    private const val MIN_HOUGH_LINE_PIXELS = 22
    private const val MAX_HOUGH_GAP_METERS = 0.10f
    private const val MAX_HOUGH_GAP_PIXELS = 10
    private const val MIN_OUTPUT_WALL_METERS = 0.46f
    private const val MAX_CANDIDATES_TO_ADJUDICATE = 72
    private const val CONNECTION_METERS = 0.34f
    private const val DUPLICATE_LINE_DISTANCE_METERS = 0.13f
    private const val MIN_DUPLICATE_ALIGNMENT = 0.985f
    private const val MIN_COVERAGE_GAIN = 0.0035f
    private const val MAX_PRECISION_LOSS = 0.010f
    private const val MAX_SCORE_LOSS = 0.0025f
    private const val MAX_ENDPOINT_SUPPORT_LOSS = 0.035f
    private const val DEFAULT_WALL_THICKNESS_METERS = 0.18f
    private const val MIN_WALL_THICKNESS_METERS = 0.09f
    private const val MAX_WALL_THICKNESS_METERS = 0.42f
    private const val BASE_HOUGH_CONFIDENCE = 0.70f
    private const val MAX_HOUGH_CONFIDENCE = 0.91f
    private const val LONG_LINE_METERS = 5.0f
    private const val LENGTH_CONFIDENCE_GAIN = 0.15f
    private const val EPSILON = 0.000001f
}
