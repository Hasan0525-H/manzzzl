package com.manzl.app.analysis

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mobile student for the heavy Raster2Seq + RoomFormer teacher ensemble.
 *
 * The shipped ONNX model predicts wall faces/corners/semantics, but its wall output is still only a
 * proposal. Candidate wall vectors are passed through the same independent source-raster fidelity
 * test used by the classical/OpenCV path. Neural confidence can never punch topology into the house
 * without matching source ink.
 */
internal class ManzlStudentFloorPlanExpert(context: Context) {
    private val models = OnnxAssetModelRepository(context)

    data class Result(
        val plan: FloorPlan,
        val modelAvailable: Boolean,
        val proposedWalls: Int,
        val acceptedWalls: Int,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        val environment = models.environmentOrNull()
            ?: return Result(seed, false, 0, 0)
        val session = models.session(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            ?: return Result(seed, false, 0, 0)
        if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
            return Result(seed, true, 0, 0)
        }

        val inputBitmap = Bitmap.createScaledBitmap(source, INPUT_SIDE, INPUT_SIDE, true)
        val inputData = preprocess(inputBitmap)
        if (inputBitmap !== source && !inputBitmap.isRecycled) inputBitmap.recycle()

        val inputTensor = runCatching {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, INPUT_SIDE.toLong(), INPUT_SIDE.toLong()),
            )
        }.getOrNull() ?: return Result(seed, true, 0, 0)

        val candidates = try {
            session.run(mapOf(INPUT_NAME to inputTensor)).use { outputs ->
                val semantic = outputs.get(SEMANTIC_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use emptyList()
                wallCandidatesFromSemanticLogits(semantic, seed)
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { inputTensor.close() }
        }

        if (candidates.isEmpty()) return Result(seed, true, 0, 0)
        val adjudicated = adjudicateAgainstSource(source, seed, candidates)
        return Result(
            plan = adjudicated.first,
            modelAvailable = true,
            proposedWalls = candidates.size,
            acceptedWalls = adjudicated.second,
        )
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(INPUT_SIDE * INPUT_SIDE)
        bitmap.getPixels(pixels, 0, INPUT_SIDE, 0, 0, INPUT_SIDE, INPUT_SIDE)
        val plane = INPUT_SIDE * INPUT_SIDE
        val result = FloatArray(plane * 3)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = ((color ushr 16) and 0xff) / 255f
            val g = ((color ushr 8) and 0xff) / 255f
            val b = (color and 0xff) / 255f
            result[index] = (r - 0.5f) / 0.5f
            result[plane + index] = (g - 0.5f) / 0.5f
            result[plane * 2 + index] = (b - 0.5f) / 0.5f
        }
        return result
    }

    private fun wallCandidatesFromSemanticLogits(
        tensor: OnnxTensor,
        seed: FloorPlan,
    ): List<WallSegment> {
        val buffer = tensor.floatBuffer ?: return emptyList()
        val expected = SEMANTIC_CLASS_COUNT * INPUT_SIDE * INPUT_SIDE
        if (buffer.remaining() < expected) return emptyList()
        val values = FloatArray(expected)
        buffer.get(values)

        val plane = INPUT_SIDE * INPUT_SIDE
        val wallMask = ByteArray(plane)
        for (pixel in 0 until plane) {
            var bestClass = 0
            var best = values[pixel]
            var runnerUp = Float.NEGATIVE_INFINITY
            for (clazz in 1 until SEMANTIC_CLASS_COUNT) {
                val value = values[clazz * plane + pixel]
                if (value > best) {
                    runnerUp = best
                    best = value
                    bestClass = clazz
                } else if (value > runnerUp) {
                    runnerUp = value
                }
            }
            if (bestClass == WALL_CLASS_ID && best - runnerUp >= MIN_WALL_LOGIT_MARGIN) {
                wallMask[pixel] = 0xff.toByte()
            }
        }

        val mask = Mat(INPUT_SIDE, INPUT_SIDE, CvType.CV_8UC1)
        val lines = Mat()
        return try {
            mask.put(0, 0, wallMask)
            Imgproc.HoughLinesP(
                mask,
                lines,
                1.0,
                Math.PI / 720.0,
                STUDENT_HOUGH_THRESHOLD,
                STUDENT_MIN_LINE_PIXELS,
                STUDENT_MAX_GAP_PIXELS,
            )
            val transform = PlanRasterTransform.forImage(seed, INPUT_SIDE, INPUT_SIDE)
            val medianThickness = seed.walls.map { it.thicknessMeters }.sorted()
                .let { it.getOrNull(it.size / 2) ?: 0.18f }
                .coerceIn(0.09f, 0.42f)
            val result = ArrayList<WallSegment>()
            for (row in 0 until lines.rows()) {
                val v = lines.get(row, 0) ?: continue
                if (v.size < 4) continue
                val start = transform.imageToPlan(v[0].toFloat(), v[1].toFloat())
                val end = transform.imageToPlan(v[2].toFloat(), v[3].toFloat())
                if (distance(start, end) < MIN_STUDENT_WALL_METERS) continue
                result += WallSegment(
                    start = start,
                    end = end,
                    thicknessMeters = medianThickness,
                    confidence = STUDENT_WALL_CONFIDENCE,
                )
            }
            result.sortedByDescending { distance(it.start, it.end) }
                .fold(ArrayList()) { accepted, candidate ->
                    if (accepted.none { nearlySameWall(it, candidate) }) accepted += candidate
                    accepted
                }
                .take(MAX_STUDENT_CANDIDATES)
        } finally {
            mask.release()
            lines.release()
        }
    }

    private fun adjudicateAgainstSource(
        source: Bitmap,
        seed: FloorPlan,
        candidates: List<WallSegment>,
    ): Pair<FloorPlan, Int> {
        val working = if (max(source.width, source.height) <= ADJUDICATION_MAX_SIDE) source else {
            val ratio = ADJUDICATION_MAX_SIDE / max(source.width, source.height).toFloat()
            Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * ratio).toInt()),
                max(1, (source.height * ratio).toInt()),
                true,
            )
        }
        return try {
            val structural = StructuralRasterMask.classify(working).mask
            var current = seed
            var report = GeometryFidelityEvaluator.evaluate(structural, working.width, working.height, current)
            var accepted = 0
            for (candidate in candidates) {
                if (current.walls.any { nearlySameWall(it, candidate) }) continue
                if (!connects(candidate, current.walls)) continue
                val trial = current.copy(walls = current.walls + candidate)
                val trialReport = GeometryFidelityEvaluator.evaluate(structural, working.width, working.height, trial)
                if (
                    trialReport.wallCoverage - report.wallCoverage >= MIN_COVERAGE_GAIN &&
                    report.wallPrecision - trialReport.wallPrecision <= MAX_PRECISION_LOSS &&
                    report.score - trialReport.score <= MAX_SCORE_LOSS
                ) {
                    current = trial.copy(geometryFidelity = trialReport)
                    report = trialReport
                    accepted++
                }
            }
            current to accepted
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun connects(candidate: WallSegment, walls: List<WallSegment>): Boolean =
        walls.any { pointSegmentDistance(candidate.start, it.start, it.end) <= CONNECTION_METERS } ||
            walls.any { pointSegmentDistance(candidate.end, it.start, it.end) <= CONNECTION_METERS } ||
            walls.any { segmentsIntersect(candidate.start, candidate.end, it.start, it.end) }

    private fun nearlySameWall(a: WallSegment, b: WallSegment): Boolean {
        val al = distance(a.start, a.end)
        val bl = distance(b.start, b.end)
        if (al <= EPSILON || bl <= EPSILON) return false
        val dot = abs(
            ((a.end.x - a.start.x) / al) * ((b.end.x - b.start.x) / bl) +
                ((a.end.z - a.start.z) / al) * ((b.end.z - b.start.z) / bl)
        )
        if (dot < DUPLICATE_ALIGNMENT) return false
        val midpoint = Vec2((b.start.x + b.end.x) * 0.5f, (b.start.z + b.end.z) * 0.5f)
        return pointSegmentDistance(midpoint, a.start, a.end) <= DUPLICATE_DISTANCE_METERS
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

    companion object {
        private const val INPUT_SIDE = 512
        private const val INPUT_NAME = "image"
        private const val SEMANTIC_OUTPUT_NAME = "semantic_logits"
        private const val SEMANTIC_CLASS_COUNT = 9
        private const val WALL_CLASS_ID = 1
        private const val MIN_WALL_LOGIT_MARGIN = 0.22f
        private const val STUDENT_HOUGH_THRESHOLD = 28
        private const val STUDENT_MIN_LINE_PIXELS = 18.0
        private const val STUDENT_MAX_GAP_PIXELS = 5.0
        private const val MIN_STUDENT_WALL_METERS = 0.42f
        private const val STUDENT_WALL_CONFIDENCE = 0.84f
        private const val MAX_STUDENT_CANDIDATES = 96
        private const val ADJUDICATION_MAX_SIDE = 2600
        private const val CONNECTION_METERS = 0.36f
        private const val DUPLICATE_ALIGNMENT = 0.982f
        private const val DUPLICATE_DISTANCE_METERS = 0.13f
        private const val MIN_COVERAGE_GAIN = 0.003f
        private const val MAX_PRECISION_LOSS = 0.009f
        private const val MAX_SCORE_LOSS = 0.002f
        private const val EPSILON = 0.000001f
    }
}
