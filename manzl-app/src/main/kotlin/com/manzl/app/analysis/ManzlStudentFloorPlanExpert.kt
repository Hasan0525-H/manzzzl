package com.manzl.app.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mobile student for the heavy Raster2Seq + RoomFormer teacher ensemble.
 *
 * Reconstruction is multi-scale: one letterboxed global 512px pass provides topology, then overlapping
 * source-space detail tiles preserve short partitions/openings that would vanish when a 4K sheet is
 * globally reduced to 512px. The same inference simultaneously decodes wall vectors and door/window/
 * stair observations; semantic observations are cached for the later fusion stage so ONNX is never
 * run twice for the same plan. All geometry remains proposal-only until source-raster adjudication.
 */
internal class ManzlStudentFloorPlanExpert(context: Context) {
    private val models = OnnxAssetModelRepository(context)

    data class Result(
        val plan: FloorPlan,
        val modelAvailable: Boolean,
        val proposedWalls: Int,
        val acceptedWalls: Int,
        val inferenceRegions: Int = 0,
        val semanticObservations: Int = 0,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        StudentSemanticEvidenceStore.clear(source)
        val environment = models.environmentOrNull()
            ?: return Result(seed, false, 0, 0)
        val session = models.session(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            ?: return Result(seed, false, 0, 0)

        val contentBounds = PlanRasterTransform.forImage(seed, source.width, source.height).bounds
        val regionLimit = detailRegionLimit(Runtime.getRuntime().maxMemory())
        val regions = StudentInferenceTilePlanner.plan(
            imageWidth = source.width,
            imageHeight = source.height,
            contentBounds = contentBounds,
            maxDetailSidePx = DETAIL_SOURCE_SIDE_PX,
            overlapFraction = DETAIL_OVERLAP_FRACTION,
            maxRegions = regionLimit,
        )
        if (regions.isEmpty()) return Result(seed, true, 0, 0)

        val candidates = ArrayList<WallSegment>()
        val semanticEvidence = ArrayList<SemanticEvidence>()
        var successfulRegions = 0
        for (region in regions) {
            val inferred = inferRegion(
                source = source,
                seed = seed,
                region = region,
                environment = environment,
                session = session,
            )
            if (inferred != null) {
                successfulRegions++
                candidates += inferred.walls
                semanticEvidence += inferred.semanticEvidence
            }
        }

        val uniqueCandidates = candidates
            .sortedWith(
                compareByDescending<WallSegment> { it.confidence }
                    .thenByDescending { distance(it.start, it.end) }
            )
            .fold(ArrayList<WallSegment>()) { accepted, candidate ->
                if (accepted.none { existing -> nearlySameWall(existing, candidate) }) accepted += candidate
                accepted
            }
            .take(MAX_MERGED_STUDENT_CANDIDATES)

        val stableSemantics = SemanticEvidenceConsensus.combine(semanticEvidence)
        StudentSemanticEvidenceStore.record(source, stableSemantics)

        if (uniqueCandidates.isEmpty()) {
            return Result(
                plan = seed,
                modelAvailable = true,
                proposedWalls = 0,
                acceptedWalls = 0,
                inferenceRegions = successfulRegions,
                semanticObservations = stableSemantics.size,
            )
        }
        val adjudicated = adjudicateAgainstSource(source, seed, uniqueCandidates)
        return Result(
            plan = adjudicated.first,
            modelAvailable = true,
            proposedWalls = uniqueCandidates.size,
            acceptedWalls = adjudicated.second,
            inferenceRegions = successfulRegions,
            semanticObservations = stableSemantics.size,
        )
    }

    private fun inferRegion(
        source: Bitmap,
        seed: FloorPlan,
        region: StudentInferenceTilePlanner.Region,
        environment: OrtEnvironment,
        session: OrtSession,
    ): RegionInference? {
        val prepared = prepareLetterboxedInput(source, region)
        val inputData = preprocess(prepared.bitmap)
        val inputTensor = runCatching {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, INPUT_SIDE.toLong(), INPUT_SIDE.toLong()),
            )
        }.getOrNull() ?: run {
            prepared.recycle()
            return null
        }

        return try {
            session.run(mapOf(INPUT_NAME to inputTensor)).use { outputs ->
                val semantic = outputs.get(SEMANTIC_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use null
                val corners = outputs.get(CORNER_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use null
                val orientation = outputs.get(ORIENTATION_OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return@use null
                decodeRegionOutputs(
                    semantic = semantic,
                    corners = corners,
                    orientation = orientation,
                    seed = seed,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    inputTransform = prepared.transform,
                    detailPass = region.kind == StudentInferenceTilePlanner.Region.Kind.DETAIL,
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { inputTensor.close() }
            prepared.recycle()
        }
    }

    private fun prepareLetterboxedInput(
        source: Bitmap,
        region: StudentInferenceTilePlanner.Region,
    ): PreparedInput {
        val regionBitmap = if (
            region.left == 0 && region.top == 0 &&
            region.rightExclusive == source.width && region.bottomExclusive == source.height
        ) {
            source
        } else {
            Bitmap.createBitmap(source, region.left, region.top, region.width, region.height)
        }
        val scale = min(INPUT_SIDE / region.width.toFloat(), INPUT_SIDE / region.height.toFloat())
        val scaledWidth = max(1, (region.width * scale).toInt())
        val scaledHeight = max(1, (region.height * scale).toInt())
        val offsetX = (INPUT_SIDE - scaledWidth) * 0.5f
        val offsetY = (INPUT_SIDE - scaledHeight) * 0.5f
        val scaled = Bitmap.createScaledBitmap(regionBitmap, scaledWidth, scaledHeight, true)
        val canvasBitmap = Bitmap.createBitmap(INPUT_SIDE, INPUT_SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            scaled,
            offsetX,
            offsetY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        if (scaled !== regionBitmap && !scaled.isRecycled) scaled.recycle()
        if (regionBitmap !== source && !regionBitmap.isRecycled) regionBitmap.recycle()
        return PreparedInput(
            bitmap = canvasBitmap,
            transform = LetterboxTransform(
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                sourceOriginX = region.left.toFloat(),
                sourceOriginY = region.top.toFloat(),
                regionWidth = region.width,
                regionHeight = region.height,
                fullSourceWidth = source.width,
                fullSourceHeight = source.height,
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

    private fun decodeRegionOutputs(
        semantic: OnnxTensor,
        corners: OnnxTensor,
        orientation: OnnxTensor,
        seed: FloorPlan,
        sourceWidth: Int,
        sourceHeight: Int,
        inputTransform: LetterboxTransform,
        detailPass: Boolean,
    ): RegionInference? {
        val heads = StudentDenseHeadDecoder.decode(
            semantic = semantic,
            corners = corners,
            orientation = orientation,
            side = INPUT_SIDE,
            wallLogitMargin = if (detailPass) DETAIL_MIN_WALL_LOGIT_MARGIN else GLOBAL_MIN_WALL_LOGIT_MARGIN,
        ) ?: return null

        val decodedWalls = StudentWallGeometryDecoder.decode(
            wallMask = heads.wallMask,
            cornerProbability = heads.cornerProbability,
            orientationX = heads.orientationX,
            orientationY = heads.orientationY,
            side = INPUT_SIDE,
            minLengthPx = if (detailPass) DETAIL_MIN_VECTOR_PIXELS else GLOBAL_MIN_VECTOR_PIXELS,
            maxSegments = if (detailPass) MAX_DETAIL_CANDIDATES_PER_REGION else MAX_GLOBAL_CANDIDATES,
        )

        val sourceTransform = PlanRasterTransform.forImage(seed, sourceWidth, sourceHeight)
        val medianThickness = seed.walls.map { it.thicknessMeters }.sorted()
            .let { values -> values.getOrNull(values.size / 2) ?: DEFAULT_WALL_THICKNESS_METERS }
            .coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS)
        val walls = decodedWalls.mapNotNull { vector ->
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

        val evidence = StudentSemanticEvidenceProjector.project(
            components = heads.semanticComponents,
            seed = seed,
            sourceTransform = sourceTransform,
            modelToSource = inputTransform::modelToSource,
            detailPass = detailPass,
        )
        return RegionInference(walls = walls, semanticEvidence = evidence)
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
                val decision = GeometryCandidateAdjudicator.decide(
                    before = report,
                    after = trialReport,
                    kind = GeometryCandidateAdjudicator.ChangeKind.ADDITION,
                )
                if (decision.accepted) {
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

    private fun detailRegionLimit(maxHeapBytes: Long): Int = when {
        maxHeapBytes >= LARGE_HEAP_BYTES -> 13
        maxHeapBytes >= MEDIUM_HEAP_BYTES -> 9
        else -> 5
    }

    private data class RegionInference(
        val walls: List<WallSegment>,
        val semanticEvidence: List<SemanticEvidence>,
    )

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
        val sourceOriginX: Float,
        val sourceOriginY: Float,
        val regionWidth: Int,
        val regionHeight: Int,
        val fullSourceWidth: Int,
        val fullSourceHeight: Int,
    ) {
        fun modelToSource(x: Float, y: Float): Pair<Float, Float>? {
            if (scale <= 0f) return null
            val localX = (x - offsetX) / scale
            val localY = (y - offsetY) / scale
            if (
                localX < -MODEL_MAPPING_MARGIN || localY < -MODEL_MAPPING_MARGIN ||
                localX > regionWidth - 1 + MODEL_MAPPING_MARGIN ||
                localY > regionHeight - 1 + MODEL_MAPPING_MARGIN
            ) return null
            val sx = sourceOriginX + localX.coerceIn(0f, regionWidth - 1f)
            val sy = sourceOriginY + localY.coerceIn(0f, regionHeight - 1f)
            return sx.coerceIn(0f, fullSourceWidth - 1f) to sy.coerceIn(0f, fullSourceHeight - 1f)
        }
    }

    companion object {
        private const val INPUT_SIDE = 512
        private const val INPUT_NAME = "image"
        private const val SEMANTIC_OUTPUT_NAME = "semantic_logits"
        private const val CORNER_OUTPUT_NAME = "corner_logits"
        private const val ORIENTATION_OUTPUT_NAME = "wall_orientation"
        private const val GLOBAL_MIN_WALL_LOGIT_MARGIN = 0.22f
        private const val DETAIL_MIN_WALL_LOGIT_MARGIN = 0.18f
        private const val GLOBAL_MIN_VECTOR_PIXELS = 18f
        private const val DETAIL_MIN_VECTOR_PIXELS = 12f
        private const val MIN_STUDENT_WALL_METERS = 0.30f
        private const val DEFAULT_WALL_THICKNESS_METERS = 0.18f
        private const val MIN_WALL_THICKNESS_METERS = 0.09f
        private const val MAX_WALL_THICKNESS_METERS = 0.42f
        private const val STUDENT_BASE_CONFIDENCE = 0.72f
        private const val STUDENT_CONFIDENCE_RANGE = 0.24f
        private const val MAX_STUDENT_CONFIDENCE = 0.95f
        private const val MAX_GLOBAL_CANDIDATES = 128
        private const val MAX_DETAIL_CANDIDATES_PER_REGION = 72
        private const val MAX_MERGED_STUDENT_CANDIDATES = 220
        private const val DETAIL_SOURCE_SIDE_PX = 1200
        private const val DETAIL_OVERLAP_FRACTION = 0.28f
        private const val ADJUDICATION_MAX_SIDE = 3000
        private const val CONNECTION_METERS = 0.36f
        private const val DUPLICATE_ALIGNMENT = 0.982f
        private const val DUPLICATE_DISTANCE_METERS = 0.13f
        private const val MODEL_MAPPING_MARGIN = 1.5f
        private const val MEDIUM_HEAP_BYTES = 320L * 1024L * 1024L
        private const val LARGE_HEAP_BYTES = 448L * 1024L * 1024L
        private const val EPSILON = 0.000001f
    }
}
