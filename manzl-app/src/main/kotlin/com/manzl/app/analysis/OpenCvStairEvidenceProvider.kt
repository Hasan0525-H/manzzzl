package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
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
 * Independent arbitrary-angle staircase expert.
 *
 * The legacy deterministic stair detector is intentionally cheap and axis-aligned. This expert uses
 * OpenCV only to obtain line segments, then a pure Kotlin regular-spacing solver looks for repeated
 * parallel treads at any angle. The result is semantic evidence only: GeometryEvidenceFusion still
 * owns the canonical staircase primitive and applies residential-size/bounds checks.
 */
internal class OpenCvStairEvidenceProvider : SemanticEvidenceProvider {

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> =
        withContext(Dispatchers.Default) {
            if (bitmap.width < 48 || bitmap.height < 48) return@withContext emptyList()
            if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
                return@withContext emptyList()
            }

            val working = bitmap.downscale(MAX_ANALYSIS_SIDE)
            val rgba = Mat()
            val gray = Mat()
            val blurred = Mat()
            val edges = Mat()
            val lines = Mat()
            try {
                Utils.bitmapToMat(working, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
                Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH, 3, true)

                val minDimension = min(working.width, working.height).coerceAtLeast(1)
                Imgproc.HoughLinesP(
                    edges,
                    lines,
                    1.0,
                    Math.PI / 720.0,
                    HOUGH_THRESHOLD,
                    max(MIN_HOUGH_LINE_PIXELS, minDimension / 95).toDouble(),
                    max(2, minDimension / 260).toDouble(),
                )

                val raw = readLines(lines)
                val candidates = ArbitraryAngleStairDetector.detect(
                    lines = raw,
                    imageWidth = working.width,
                    imageHeight = working.height,
                )
                if (candidates.isEmpty()) return@withContext emptyList()

                val transform = PlanRasterTransform.forImage(structuralPlan, working.width, working.height)
                candidates.mapNotNull { candidate ->
                    candidate.toEvidence(transform)
                }
            } catch (_: RuntimeException) {
                emptyList()
            } finally {
                rgba.release()
                gray.release()
                blurred.release()
                edges.release()
                lines.release()
                if (working !== bitmap && !working.isRecycled) working.recycle()
            }
        }

    private fun readLines(lines: Mat): List<ArbitraryAngleStairDetector.RasterLine> {
        if (lines.empty()) return emptyList()
        val result = ArrayList<ArbitraryAngleStairDetector.RasterLine>()
        for (row in 0 until lines.rows()) {
            val values = lines.get(row, 0) ?: continue
            if (values.size < 4) continue
            result += ArbitraryAngleStairDetector.RasterLine(
                x0 = values[0].toFloat(),
                y0 = values[1].toFloat(),
                x1 = values[2].toFloat(),
                y1 = values[3].toFloat(),
            )
        }
        return result
    }

    private fun ArbitraryAngleStairDetector.Candidate.toEvidence(
        transform: PlanRasterTransform,
    ): SemanticEvidence? {
        val treadRadians = treadAngleDegrees * PI.toFloat() / 180f
        val tux = cos(treadRadians)
        val tuy = sin(treadRadians)
        val rux = -tuy
        val ruy = tux

        val center = transform.imageToPlan(centerX, centerY)
        val widthA = transform.imageToPlan(
            centerX - tux * treadLengthPx * 0.5f,
            centerY - tuy * treadLengthPx * 0.5f,
        )
        val widthB = transform.imageToPlan(
            centerX + tux * treadLengthPx * 0.5f,
            centerY + tuy * treadLengthPx * 0.5f,
        )
        val runA = transform.imageToPlan(
            centerX - rux * runLengthPx * 0.5f,
            centerY - ruy * runLengthPx * 0.5f,
        )
        val runB = transform.imageToPlan(
            centerX + rux * runLengthPx * 0.5f,
            centerY + ruy * runLengthPx * 0.5f,
        )
        val widthMeters = distance(widthA, widthB)
        val runMeters = distance(runA, runB)
        if (widthMeters !in MIN_STAIR_WIDTH_METERS..MAX_STAIR_WIDTH_METERS) return null
        if (runMeters !in MIN_STAIR_RUN_METERS..MAX_STAIR_RUN_METERS) return null

        val rotation = normalizeFullTurn(
            Math.toDegrees(
                atan2(
                    (runB.z - runA.z).toDouble(),
                    (runB.x - runA.x).toDouble(),
                )
            ).toFloat()
        )
        return SemanticEvidence(
            kind = SemanticKind.STAIR,
            center = center,
            widthMeters = widthMeters,
            lengthMeters = runMeters,
            rotationDegrees = rotation,
            confidence = confidence,
            source = EvidenceSource.CLASSICAL_CV,
        )
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun normalizeFullTurn(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
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

    companion object {
        private const val MAX_ANALYSIS_SIDE = 1800
        private const val CANNY_LOW = 55.0
        private const val CANNY_HIGH = 155.0
        private const val HOUGH_THRESHOLD = 13
        private const val MIN_HOUGH_LINE_PIXELS = 8
        private const val MIN_STAIR_WIDTH_METERS = 0.70f
        private const val MAX_STAIR_WIDTH_METERS = 2.80f
        private const val MIN_STAIR_RUN_METERS = 1.45f
        private const val MAX_STAIR_RUN_METERS = 9.00f
    }
}

/** Pure line-geometry solver used by [OpenCvStairEvidenceProvider] and JVM regression tests. */
internal object ArbitraryAngleStairDetector {

    data class RasterLine(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) {
        val length: Float
            get() {
                val dx = x1 - x0
                val dy = y1 - y0
                return sqrt(dx * dx + dy * dy)
            }
        val centerX: Float get() = (x0 + x1) * 0.5f
        val centerY: Float get() = (y0 + y1) * 0.5f
        val angleDegrees: Float
            get() = normalizeHalfTurn(Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat())
    }

    data class Candidate(
        val centerX: Float,
        val centerY: Float,
        val treadLengthPx: Float,
        val runLengthPx: Float,
        val treadAngleDegrees: Float,
        val treadCount: Int,
        val confidence: Float,
    )

    fun detect(
        lines: List<RasterLine>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<Candidate> {
        if (lines.size < MIN_TREADS || imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val minDimension = min(imageWidth, imageHeight).toFloat().coerceAtLeast(1f)
        val maxTreadPixels = minDimension * MAX_TREAD_FRACTION
        val usable = lines.filter { line ->
            line.length in MIN_TREAD_PIXELS..maxTreadPixels
        }
        if (usable.size < MIN_TREADS) return emptyList()

        val rawCandidates = ArrayList<Candidate>()
        for (seedIndex in usable.indices step SEED_STRIDE) {
            val seed = usable[seedIndex]
            val seedLength = seed.length.coerceAtLeast(1f)
            val compatible = usable.filter { line ->
                axisAngleDifference(line.angleDegrees, seed.angleDegrees) <= MAX_ANGLE_DELTA_DEGREES &&
                    line.length / seedLength in MIN_LENGTH_RATIO..MAX_LENGTH_RATIO
            }
            if (compatible.size < MIN_TREADS) continue
            fitGroup(compatible, minDimension)?.let(rawCandidates::add)
        }

        return rawCandidates
            .sortedByDescending { it.confidence }
            .fold(ArrayList<Candidate>()) { accepted, candidate ->
                val duplicate = accepted.any { existing -> duplicate(existing, candidate, minDimension) }
                if (!duplicate) accepted += candidate
                accepted
            }
            .take(MAX_CANDIDATES)
    }

    private fun fitGroup(lines: List<RasterLine>, minDimension: Float): Candidate? {
        val angle = meanAxisAngle(lines)
        val radians = angle * PI.toFloat() / 180f
        val ux = cos(radians)
        val uy = sin(radians)
        val nx = -uy
        val ny = ux

        val projected = lines.map { line ->
            val a = line.x0 * ux + line.y0 * uy
            val b = line.x1 * ux + line.y1 * uy
            val normal = line.centerX * nx + line.centerY * ny
            ProjectedLine(
                source = line,
                from = min(a, b),
                to = max(a, b),
                normal = normal,
            )
        }.sortedBy { it.normal }

        // Canny often gives two almost-identical edges for one tread. Collapse them before spacing
        // statistics so a thick stroke cannot masquerade as twice the number of steps.
        val normalMerge = max(MIN_DUPLICATE_NORMAL_PIXELS, minDimension * DUPLICATE_NORMAL_FRACTION)
        val clusters = ArrayList<MutableList<ProjectedLine>>()
        for (line in projected) {
            val last = clusters.lastOrNull()
            if (last == null || abs(last.map { it.normal }.average().toFloat() - line.normal) > normalMerge) {
                clusters += mutableListOf(line)
            } else {
                last += line
            }
        }
        val representatives = clusters.map { cluster ->
            cluster.maxByOrNull { it.to - it.from } ?: cluster.first()
        }
        if (representatives.size !in MIN_TREADS..MAX_TREADS) return null

        val lengths = representatives.map { it.to - it.from }
        val medianLength = median(lengths)
        if (medianLength < MIN_TREAD_PIXELS) return null
        val lengthError = lengths.sumOf { value ->
            (abs(value - medianLength) / medianLength.coerceAtLeast(1f)).toDouble()
        }.toFloat() / lengths.size
        if (lengthError > MAX_LENGTH_ERROR) return null

        val overlapAnchorFrom = median(representatives.map { it.from })
        val overlapAnchorTo = median(representatives.map { it.to })
        val anchorLength = (overlapAnchorTo - overlapAnchorFrom).coerceAtLeast(1f)
        val overlapping = representatives.filter { line ->
            val overlap = max(0f, min(line.to, overlapAnchorTo) - max(line.from, overlapAnchorFrom))
            overlap / min((line.to - line.from).coerceAtLeast(1f), anchorLength) >= MIN_AXIS_OVERLAP
        }
        if (overlapping.size < MIN_TREADS) return null

        val normals = overlapping.map { it.normal }.sorted()
        val deltas = normals.zipWithNext { a, b -> b - a }.filter { it > normalMerge * 0.65f }
        if (deltas.size < MIN_TREADS - 1) return null
        val medianSpacing = median(deltas)
        val minSpacing = max(MIN_SPACING_PIXELS, minDimension * MIN_SPACING_FRACTION)
        val maxSpacing = max(minSpacing + 1f, minDimension * MAX_SPACING_FRACTION)
        if (medianSpacing !in minSpacing..maxSpacing) return null
        val spacingError = deltas.sumOf { delta ->
            (abs(delta - medianSpacing) / medianSpacing.coerceAtLeast(1f)).toDouble()
        }.toFloat() / deltas.size
        if (spacingError > MAX_SPACING_ERROR) return null

        val centerAlongValues = overlapping.map { (it.from + it.to) * 0.5f }
        val centerAlong = median(centerAlongValues)
        val maxCenterDrift = centerAlongValues.maxOf { abs(it - centerAlong) }
        if (maxCenterDrift > medianLength * MAX_CENTER_DRIFT_RATIO) return null

        val minNormal = normals.first()
        val maxNormal = normals.last()
        val runLength = maxNormal - minNormal + medianSpacing
        if (runLength < medianLength * MIN_RUN_TO_WIDTH_RATIO) return null
        if (runLength > minDimension * MAX_RUN_FRACTION) return null

        val centerNormal = (minNormal + maxNormal) * 0.5f
        val centerX = ux * centerAlong + nx * centerNormal
        val centerY = uy * centerAlong + ny * centerNormal
        if (centerX !in 0f..imageSafeMax(minDimension, centerX) || centerY < 0f) return null

        val countScore = ((overlapping.size - MIN_TREADS) / 9f).coerceIn(0f, 1f)
        val spacingScore = (1f - spacingError / MAX_SPACING_ERROR).coerceIn(0f, 1f)
        val lengthScore = (1f - lengthError / MAX_LENGTH_ERROR).coerceIn(0f, 1f)
        val alignmentScore = (1f - maxCenterDrift / (medianLength * MAX_CENTER_DRIFT_RATIO).coerceAtLeast(1f))
            .coerceIn(0f, 1f)
        val confidence = (
            0.66f + countScore * 0.10f + spacingScore * 0.08f + lengthScore * 0.06f + alignmentScore * 0.05f
            ).coerceIn(0f, 0.95f)

        return Candidate(
            centerX = centerX,
            centerY = centerY,
            treadLengthPx = medianLength,
            runLengthPx = runLength,
            treadAngleDegrees = angle,
            treadCount = overlapping.size,
            confidence = confidence,
        )
    }

    private fun meanAxisAngle(lines: List<RasterLine>): Float {
        var x = 0.0
        var y = 0.0
        var weight = 0.0
        for (line in lines) {
            val radians = line.angleDegrees * PI / 180.0
            val w = line.length.coerceAtLeast(1f).toDouble()
            x += cos(radians * 2.0) * w
            y += sin(radians * 2.0) * w
            weight += w
        }
        if (weight <= 0.0) return 0f
        return normalizeHalfTurn(Math.toDegrees(0.5 * atan2(y, x)).toFloat())
    }

    private fun duplicate(a: Candidate, b: Candidate, minDimension: Float): Boolean {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val distance = sqrt(dx * dx + dy * dy)
        return distance <= max(MIN_DUPLICATE_CENTER_PIXELS, minDimension * DUPLICATE_CENTER_FRACTION) &&
            axisAngleDifference(a.treadAngleDegrees, b.treadAngleDegrees) <= MAX_DUPLICATE_ANGLE_DEGREES
    }

    private fun axisAngleDifference(a: Float, b: Float): Float {
        val na = normalizeHalfTurn(a)
        val nb = normalizeHalfTurn(b)
        val delta = abs(na - nb)
        return min(delta, 180f - delta)
    }

    private fun normalizeHalfTurn(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5f else sorted[middle]
    }

    // The real image bounds are checked again by PlanRasterTransform in the provider. This helper
    // only rejects obviously invalid negative/overflow arithmetic without making the pure solver
    // depend on an Android bitmap.
    private fun imageSafeMax(minDimension: Float, value: Float): Float = max(minDimension * 8f, value)

    private data class ProjectedLine(
        val source: RasterLine,
        val from: Float,
        val to: Float,
        val normal: Float,
    )

    private const val MIN_TREADS = 5
    private const val MAX_TREADS = 28
    private const val SEED_STRIDE = 2
    private const val MIN_TREAD_PIXELS = 7f
    private const val MAX_TREAD_FRACTION = 0.34f
    private const val MAX_ANGLE_DELTA_DEGREES = 5.5f
    private const val MIN_LENGTH_RATIO = 0.58f
    private const val MAX_LENGTH_RATIO = 1.55f
    private const val MIN_DUPLICATE_NORMAL_PIXELS = 1.5f
    private const val DUPLICATE_NORMAL_FRACTION = 0.0016f
    private const val MIN_AXIS_OVERLAP = 0.52f
    private const val MAX_LENGTH_ERROR = 0.31f
    private const val MIN_SPACING_PIXELS = 2.0f
    private const val MIN_SPACING_FRACTION = 0.0017f
    private const val MAX_SPACING_FRACTION = 0.060f
    private const val MAX_SPACING_ERROR = 0.30f
    private const val MAX_CENTER_DRIFT_RATIO = 0.30f
    private const val MIN_RUN_TO_WIDTH_RATIO = 0.48f
    private const val MAX_RUN_FRACTION = 0.72f
    private const val MIN_DUPLICATE_CENTER_PIXELS = 8f
    private const val DUPLICATE_CENTER_FRACTION = 0.035f
    private const val MAX_DUPLICATE_ANGLE_DEGREES = 8f
    private const val MAX_CANDIDATES = 8
}
