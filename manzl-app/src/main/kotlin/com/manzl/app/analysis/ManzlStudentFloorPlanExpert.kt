package com.manzl.app.analysis

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mobile student for the heavy Raster2Seq + RoomFormer teacher ensemble.
 *
 * The model has three heads: semantic wall/feature logits, corner logits and wall orientation. The
 * runtime keeps the source aspect ratio through letterboxing, then decodes arbitrary-angle vectors
 * from all three heads instead of collapsing the student output back to Hough lines. Every vector is
 * still only a proposal and must improve independent source-raster fidelity before it is accepted.
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

        val prepared = prepareLetterboxedInput(source)
        val inputData = preprocess(prepared.bitmap)
        val inputTensor = runCatching {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, INPUT_SIDE.toLong(), INPUT_SIDE.toLong()),
            )
        }.getOrNull() ?: run {
            prepared.recycle()
            return Result(seed, true, 0, 0)
        }

        val candidates = try {
            session.run(mapOf(INPUT_NAME to inputTensor)).use { outputs ->
                val semantic = outputs.get(SEMANTIC_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use emptyList()
                val corners = outputs.get(CORNER_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use emptyList()
                val orientation = outputs.get(ORIENTATION_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use emptyList()
                wallCandidatesFromOutputs(
                    semantic = semantic,
                    corners = corners,
                    orientation = orientation,
                    seed = seed,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    inputTransform = prepared.transform,
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { inputTensor.close() }
            prepared.recycle()
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

    private fun prepareLetterboxedInput(source: Bitmap): PreparedInput {
        val scale = min(INPUT_SIDE / source.width.toFloat(), INPUT_SIDE / source.height.toFloat())
        val scaledWidth = max(1, (source.width * scale).toInt())
        val scaledHeight = max(1, (source.height * scale).toInt())
        val offsetX = (INPUT_SIDE - scaledWidth) * 0.5f
        val offsetY = (INPUT_SIDE - scaledHeight) * 0.5f
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val canvasBitmap = Bitmap.createBitmap(INPUT_SIDE, INPUT_SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            scaled,
            offsetX,
            offsetY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return PreparedInput(
            bitmap = canvasBitmap,
            transform = LetterboxTransform(
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                sourceWidth = source.width,
                sourceHeight = source.height,
            ),
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

    private fun wallCandidatesFromOutputs(
        semantic: OnnxTensor,
        corners: OnnxTensor,
        orientation: OnnxTensor,
        seed: FloorPlan,
        sourceWidth: Int,
        sourceHeight: Int,
        inputTransform: LetterboxTransform,
    ): List<WallSegment> {
        val plane = INPUT_SIDE * INPUT_SIDE
        val semanticValues = semantic.floatBuffer?.let { buffer ->
            if (buffer.remaining() < SEMANTIC_CLASS_COUNT * plane) return emptyList()
            FloatArray(SEMANTIC_CLASS_COUNT * plane).also(buffer::get)
        } ?: return emptyList()
        val cornerValues = corners.floatBuffer?.let { buffer ->
            if (buffer.remaining() < plane) return emptyList()
            FloatArray(plane).also(buffer::get)
        } ?: return emptyList()
        val orientationValues = orientation.floatBuffer?.let { buffer ->
            if (buffer.remaining() < 2 * plane) return emptyList()
            FloatArray(2 * plane).also(buffer::get)
        } ?: return emptyList()

        val wallMask = BooleanArray(plane)
        for (pixel in 0 until plane) {
            var bestClass = 0
            var best = semanticValues[pixel]
            var runnerUp = Float.NEGATIVE_INFINITY
            for (clazz in 1 until SEMANTIC_CLASS_COUNT) {
                val value = semanticValues[clazz * plane + pixel]
                if (value > best) {
                    runnerUp = best
                    best = value
                    bestClass = clazz
                } else if (value > runnerUp) {
                    runnerUp = value
                }
            }
            wallMask[pixel] = bestClass == WALL_CLASS_ID && best - runnerUp >= MIN_WALL_LOGIT_MARGIN
        }

        val cornerProbability = FloatArray(plane) { index -> sigmoid(cornerValues[index]) }
        val orientationX = orientationValues.copyOfRange(0, plane)
        val orientationY = orientationValues.copyOfRange(plane, plane * 2)
        val decoded = StudentWallGeometryDecoder.decode(
            wallMask = wallMask,
            cornerProbability = cornerProbability,
            orientationX = orientationX,
            orientationY = orientationY,
            side = INPUT_SIDE,
            minLengthPx = MIN_STUDENT_VECTOR_PIXELS,
            maxSegments = MAX_STUDENT_CANDIDATES,
        )
        if (decoded.isEmpty()) return emptyList()

        val sourceTransform = PlanRasterTransform.forImage(seed, sourceWidth, sourceHeight)
        val medianThickness = seed.walls.map { it.thicknessMeters }.sorted()
            .let { values -> values.getOrNull(values.size / 2) ?: DEFAULT_WALL_THICKNESS_METERS }
            .coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS)

        return decoded.mapNotNull { vector ->
            val sourceStart = inputTransform.modelToSource(vector.x0, vector.y0) ?: return@mapNotNull null
            val sourceEnd = inputTransform.modelToSource(vector.x1, vector.y1) ?: return@mapNotNull null
            val start = sourceTransform.imageToPlan(sourceStart.first, sourceStart.second)
            val end = sourceTransform.imageToPlan(sourceEnd.first, sourceEnd.second)
            if (distance(start, end) < MIN_STUDENT_WALL_METERS) return@mapNotNull null
            WallSegment(
                start = start,
                end = end,
                thicknessMeters = medianThickness,
                confidence = (STUDENT_BASE_CONFIDENCE + vector.confidence * STUDENT_CONFIDENCE_RANGE)
                    .coerceIn(STUDENT_BASE_CONFIDENCE, MAX_STUDENT_CONFIDENCE),
            )
        }.sortedByDescending { distance(it.start, it.end) }
            .fold(ArrayList<WallSegment>()) { accepted, candidate ->
                if (accepted.none { existing -> nearlySameWall(existing, candidate) }) accepted += candidate
                accepted
            }
            .take(MAX_STUDENT_CANDIDATES)
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

    private fun sigmoid(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    private data class PreparedInput(
        val bitmap: Bitmap,
        val transform: LetterboxTransform,
    ) {
        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private data class LetterboxTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) {
        fun modelToSource(x: Float, y: Float): Pair<Float, Float>? {
            if (scale <= 0f) return null
            val sx = (x - offsetX) / scale
            val sy = (y - offsetY) / scale
            if (sx < -MODEL_MAPPING_MARGIN || sy < -MODEL_MAPPING_MARGIN ||
                sx > sourceWidth - 1 + MODEL_MAPPING_MARGIN || sy > sourceHeight - 1 + MODEL_MAPPING_MARGIN
            ) return null
            return sx.coerceIn(0f, sourceWidth - 1f) to sy.coerceIn(0f, sourceHeight - 1f)
        }
    }

    companion object {
        private const val INPUT_SIDE = 512
        private const val INPUT_NAME = "image"
        private const val SEMANTIC_OUTPUT_NAME = "semantic_logits"
        private const val CORNER_OUTPUT_NAME = "corner_logits"
        private const val ORIENTATION_OUTPUT_NAME = "wall_orientation"
        private const val SEMANTIC_CLASS_COUNT = 9
        private const val WALL_CLASS_ID = 1
        private const val MIN_WALL_LOGIT_MARGIN = 0.22f
        private const val MIN_STUDENT_VECTOR_PIXELS = 18f
        private const val MIN_STUDENT_WALL_METERS = 0.42f
        private const val DEFAULT_WALL_THICKNESS_METERS = 0.18f
        private const val MIN_WALL_THICKNESS_METERS = 0.09f
        private const val MAX_WALL_THICKNESS_METERS = 0.42f
        private const val STUDENT_BASE_CONFIDENCE = 0.74f
        private const val STUDENT_CONFIDENCE_RANGE = 0.22f
        private const val MAX_STUDENT_CONFIDENCE = 0.95f
        private const val MAX_STUDENT_CANDIDATES = 128
        private const val ADJUDICATION_MAX_SIDE = 2800
        private const val CONNECTION_METERS = 0.36f
        private const val DUPLICATE_ALIGNMENT = 0.982f
        private const val DUPLICATE_DISTANCE_METERS = 0.13f
        private const val MIN_COVERAGE_GAIN = 0.0025f
        private const val MAX_PRECISION_LOSS = 0.008f
        private const val MAX_SCORE_LOSS = 0.0015f
        private const val MODEL_MAPPING_MARGIN = 1.5f
        private const val EPSILON = 0.000001f
    }
}
