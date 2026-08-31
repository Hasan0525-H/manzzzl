package com.manzl.app.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-resolution wall-face refiner backed by bundled MobileSAM ONNX models.
 *
 * MobileSAM never creates a wall, room or opening here. It receives a narrow box prompt around an
 * already measured wall and may only adjust that wall's two end faces and measured thickness within
 * strict physical limits. The entire refined plan is then re-rasterized against the source. If global
 * coverage/precision/endpoint support do not improve safely, every MobileSAM edit is discarded.
 */
internal class MobileSamBoundaryRefiner(context: Context) {
    private val models = OnnxAssetModelRepository(context)

    data class Result(
        val plan: FloorPlan,
        val runtimeAvailable: Boolean,
        val attemptedWalls: Int,
        val refinedWalls: Int,
        val accepted: Boolean,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        if (seed.walls.size < 4 || source.width <= 32 || source.height <= 32) {
            return Result(seed, false, 0, 0, false)
        }
        val env = models.environmentOrNull() ?: return Result(seed, false, 0, 0, false)
        val encoder = models.session(UltraModelCatalog.MOBILE_SAM_ENCODER)
            ?: return Result(seed, false, 0, 0, false)
        val decoder = models.session(UltraModelCatalog.MOBILE_SAM_DECODER)
            ?: return Result(seed, false, 0, 0, false)

        val working = source.downscale(REFINEMENT_MAX_SIDE)
        return try {
            val encoded = encodeImage(working, env, encoder)
                ?: return Result(seed, true, 0, 0, false)
            val transform = PlanRasterTransform.forImage(seed, working.width, working.height)
            val pixelsPerMeter = min(transform.pixelsPerMeterX, transform.pixelsPerMeterZ)
            if (!pixelsPerMeter.isFinite() || pixelsPerMeter < MIN_PIXELS_PER_METER) {
                return Result(seed, true, 0, 0, false)
            }

            val orderedIndices = seed.walls.indices.sortedWith(
                compareBy<Int> { seed.walls[it].confidence }
                    .thenByDescending { wallLength(seed.walls[it]) }
            ).take(MAX_REFINED_WALLS)

            val updated = seed.walls.toMutableList()
            var refined = 0
            for (index in orderedIndices) {
                val wall = updated[index]
                val mask = decodeWallMask(
                    decoder = decoder,
                    env = env,
                    encoded = encoded,
                    wall = wall,
                    transform = transform,
                    imageWidth = working.width,
                    imageHeight = working.height,
                    pixelsPerMeter = pixelsPerMeter,
                ) ?: continue
                val candidate = candidateFromMask(
                    wall = wall,
                    mask = mask,
                    transform = transform,
                    imageWidth = working.width,
                    imageHeight = working.height,
                    pixelsPerMeter = pixelsPerMeter,
                ) ?: continue
                if (!safeLocalRefinement(wall, candidate)) continue
                updated[index] = candidate
                refined++
            }

            if (refined == 0) {
                return Result(seed, true, orderedIndices.size, 0, false)
            }

            val structuralMask = StructuralRasterMask.classify(working).mask
            val seedReport = GeometryFidelityEvaluator.evaluate(
                structuralMask,
                working.width,
                working.height,
                seed,
            )
            val trial = seed.copy(walls = updated)
            val trialReport = GeometryFidelityEvaluator.evaluate(
                structuralMask,
                working.width,
                working.height,
                trial,
            )
            val accepted = refinementImprovesSafely(seedReport, trialReport)
            if (!accepted) {
                Result(seed, true, orderedIndices.size, refined, false)
            } else {
                Result(
                    plan = trial.copy(geometryFidelity = trialReport),
                    runtimeAvailable = true,
                    attemptedWalls = orderedIndices.size,
                    refinedWalls = refined,
                    accepted = true,
                )
            }
        } catch (_: Exception) {
            Result(seed, true, 0, 0, false)
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private data class EncodedImage(
        val embedding: FloatArray,
        val embeddingShape: LongArray,
        val imageScale: Float,
    )

    private data class MaskOutput(
        val values: FloatArray,
        val width: Int,
        val height: Int,
    )

    private fun encodeImage(
        bitmap: Bitmap,
        env: OrtEnvironment,
        encoder: OrtSession,
    ): EncodedImage? {
        val longest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = SAM_LONG_SIDE / longest.toFloat()
        val resizedWidth = max(1, (bitmap.width * scale).toInt()).coerceAtMost(SAM_LONG_SIDE)
        val resizedHeight = max(1, (bitmap.height * scale).toInt()).coerceAtMost(SAM_LONG_SIDE)
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)

        val inputName = encoder.inputNames.firstOrNull() ?: run {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
            return null
        }
        val tensorInfo = encoder.inputInfo[inputName]?.info as? TensorInfo
        val shape = tensorInfo?.shape
        val expectsHwcPreprocessing = shape?.size == 3
        val input = if (expectsHwcPreprocessing) {
            rawHwc(resized)
        } else {
            normalizedPaddedNchw(resized)
        }
        val inputShape = if (expectsHwcPreprocessing) {
            longArrayOf(resizedHeight.toLong(), resizedWidth.toLong(), 3L)
        } else {
            longArrayOf(1L, 3L, SAM_LONG_SIDE.toLong(), SAM_LONG_SIDE.toLong())
        }
        if (resized !== bitmap && !resized.isRecycled) resized.recycle()

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape)
        return try {
            encoder.run(mapOf(inputName to tensor)).use { outputs ->
                val embeddingTensor = outputs.get("image_embeddings").orElse(null) as? OnnxTensor
                    ?: outputs.get(0) as? OnnxTensor
                    ?: return@use null
                val buffer = embeddingTensor.floatBuffer ?: return@use null
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                EncodedImage(
                    embedding = values,
                    embeddingShape = embeddingTensor.info.shape.clone(),
                    imageScale = scale,
                )
            }
        } finally {
            tensor.close()
        }
    }

    private fun decodeWallMask(
        decoder: OrtSession,
        env: OrtEnvironment,
        encoded: EncodedImage,
        wall: WallSegment,
        transform: PlanRasterTransform,
        imageWidth: Int,
        imageHeight: Int,
        pixelsPerMeter: Float,
    ): MaskOutput? {
        val start = transform.planToImage(wall.start)
        val end = transform.planToImage(wall.end)
        val halfBox = max(
            MIN_PROMPT_HALF_WIDTH_PX,
            wall.thicknessMeters * pixelsPerMeter * PROMPT_THICKNESS_MULTIPLIER + PROMPT_CLEARANCE_PX,
        )
        val minX = (min(start.first, end.first) - halfBox).coerceIn(0f, imageWidth - 1f)
        val minY = (min(start.second, end.second) - halfBox).coerceIn(0f, imageHeight - 1f)
        val maxX = (max(start.first, end.first) + halfBox).coerceIn(0f, imageWidth - 1f)
        val maxY = (max(start.second, end.second) + halfBox).coerceIn(0f, imageHeight - 1f)
        if (maxX - minX < MIN_PROMPT_SPAN_PX || maxY - minY < MIN_PROMPT_SPAN_PX) return null

        val coords = floatArrayOf(
            minX * encoded.imageScale,
            minY * encoded.imageScale,
            maxX * encoded.imageScale,
            maxY * encoded.imageScale,
        )
        val labels = floatArrayOf(2f, 3f)
        val maskInput = FloatArray(SAM_MASK_INPUT_SIDE * SAM_MASK_INPUT_SIDE)
        val noMask = floatArrayOf(0f)
        val originalSize = floatArrayOf(imageHeight.toFloat(), imageWidth.toFloat())

        val tensors = LinkedHashMap<String, OnnxTensor>()
        fun add(name: String, data: FloatArray, shape: LongArray) {
            if (name in decoder.inputNames) {
                tensors[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }
        }
        add("image_embeddings", encoded.embedding, encoded.embeddingShape)
        add("point_coords", coords, longArrayOf(1L, 2L, 2L))
        add("point_labels", labels, longArrayOf(1L, 2L))
        add("mask_input", maskInput, longArrayOf(1L, 1L, SAM_MASK_INPUT_SIDE.toLong(), SAM_MASK_INPUT_SIDE.toLong()))
        add("has_mask_input", noMask, longArrayOf(1L))
        add("orig_im_size", originalSize, longArrayOf(2L))
        if (!decoder.inputNames.all { it in tensors }) {
            tensors.values.forEach { it.close() }
            return null
        }

        return try {
            decoder.run(tensors).use { outputs ->
                val maskTensor = outputs.get("masks").orElse(null) as? OnnxTensor
                    ?: outputs.get(0) as? OnnxTensor
                    ?: return@use null
                val shape = maskTensor.info.shape
                if (shape.size < 2) return@use null
                val height = shape[shape.lastIndex - 1].toInt()
                val width = shape[shape.lastIndex].toInt()
                if (width <= 0 || height <= 0) return@use null
                val buffer = maskTensor.floatBuffer ?: return@use null
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                if (values.size < width * height) return@use null
                // Single-mask export is expected. If a leading batch/channel exists, the first mask
                // occupies the first width*height values.
                MaskOutput(values.copyOf(width * height), width, height)
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun candidateFromMask(
        wall: WallSegment,
        mask: MaskOutput,
        transform: PlanRasterTransform,
        imageWidth: Int,
        imageHeight: Int,
        pixelsPerMeter: Float,
    ): WallSegment? {
        val start = transform.planToImage(wall.start)
        val end = transform.planToImage(wall.end)
        val dx = end.first - start.first
        val dy = end.second - start.second
        val lengthPx = sqrt(dx * dx + dy * dy)
        if (lengthPx < MIN_WALL_CONTEXT_PX) return null
        val ux = dx / lengthPx
        val uy = dy / lengthPx
        val nx = -uy
        val ny = ux
        val corridor = max(
            MIN_MASK_CORRIDOR_PX,
            wall.thicknessMeters * pixelsPerMeter * MASK_CORRIDOR_THICKNESS_MULTIPLIER + MASK_CORRIDOR_CLEARANCE_PX,
        )

        val minX = floor(min(start.first, end.first) - corridor).toInt().coerceIn(0, imageWidth - 1)
        val maxX = ceil(max(start.first, end.first) + corridor).toInt().coerceIn(0, imageWidth - 1)
        val minY = floor(min(start.second, end.second) - corridor).toInt().coerceIn(0, imageHeight - 1)
        val maxY = ceil(max(start.second, end.second) + corridor).toInt().coerceIn(0, imageHeight - 1)

        val alongSamples = ArrayList<Float>()
        val normalSamples = ArrayList<Float>()
        for (y in minY..maxY step MASK_PIXEL_STEP) {
            val my = ((y + 0.5f) * mask.height / imageHeight.toFloat()).toInt().coerceIn(0, mask.height - 1)
            for (x in minX..maxX step MASK_PIXEL_STEP) {
                val mx = ((x + 0.5f) * mask.width / imageWidth.toFloat()).toInt().coerceIn(0, mask.width - 1)
                val value = mask.values[my * mask.width + mx]
                if (value <= SAM_MASK_THRESHOLD) continue
                val rx = x + 0.5f - start.first
                val ry = y + 0.5f - start.second
                val along = rx * ux + ry * uy
                val normal = rx * nx + ry * ny
                if (normal !in -corridor..corridor) continue
                if (along !in -ENDPOINT_SEARCH_EXTENSION_PX..(lengthPx + ENDPOINT_SEARCH_EXTENSION_PX)) continue
                alongSamples += along
                normalSamples += normal
            }
        }
        if (alongSamples.size < MIN_MASK_SAMPLES) return null
        alongSamples.sort()
        normalSamples.sort()

        val from = quantile(alongSamples, ALONG_LOW_QUANTILE)
        val to = quantile(alongSamples, ALONG_HIGH_QUANTILE)
        if (to - from < lengthPx * MIN_RETAINED_WALL_RATIO) return null
        val normalLow = quantile(normalSamples, NORMAL_LOW_QUANTILE)
        val normalHigh = quantile(normalSamples, NORMAL_HIGH_QUANTILE)
        val thicknessMeters = ((normalHigh - normalLow).coerceAtLeast(1f) / pixelsPerMeter)
            .coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS)

        val originalFrom = 0f
        val originalTo = lengthPx
        val maxEndpointShift = MAX_ENDPOINT_SHIFT_METERS * pixelsPerMeter
        val safeFrom = from.coerceIn(originalFrom - maxEndpointShift, originalFrom + maxEndpointShift)
        val safeTo = to.coerceIn(originalTo - maxEndpointShift, originalTo + maxEndpointShift)
        if (safeTo - safeFrom < MIN_REFINED_WALL_METERS * pixelsPerMeter) return null

        val startPixelX = start.first + ux * safeFrom
        val startPixelY = start.second + uy * safeFrom
        val endPixelX = start.first + ux * safeTo
        val endPixelY = start.second + uy * safeTo
        return wall.copy(
            start = transform.imageToPlan(startPixelX, startPixelY),
            end = transform.imageToPlan(endPixelX, endPixelY),
            thicknessMeters = thicknessMeters,
            confidence = max(wall.confidence, MOBILE_SAM_REFINEMENT_CONFIDENCE).coerceAtMost(0.98f),
        )
    }

    private fun safeLocalRefinement(original: WallSegment, candidate: WallSegment): Boolean {
        if (wallLength(candidate) < MIN_REFINED_WALL_METERS) return false
        val thicknessRatio = candidate.thicknessMeters / original.thicknessMeters.coerceAtLeast(0.01f)
        if (thicknessRatio !in MIN_THICKNESS_RATIO..MAX_THICKNESS_RATIO) return false
        if (distance(original.start, candidate.start) > MAX_ENDPOINT_SHIFT_METERS + 0.01f) return false
        if (distance(original.end, candidate.end) > MAX_ENDPOINT_SHIFT_METERS + 0.01f) return false
        return true
    }

    private fun refinementImprovesSafely(
        before: com.manzl.app.model.GeometryFidelityReport,
        after: com.manzl.app.model.GeometryFidelityReport,
    ): Boolean {
        if (after.wallCoverage < before.wallCoverage - MAX_GLOBAL_COVERAGE_LOSS) return false
        if (after.wallPrecision < before.wallPrecision - MAX_GLOBAL_PRECISION_LOSS) return false
        if (after.endpointSupport < before.endpointSupport - MAX_GLOBAL_ENDPOINT_LOSS) return false
        if (after.score < before.score - MAX_GLOBAL_SCORE_LOSS) return false
        return after.score >= before.score + MIN_GLOBAL_SCORE_GAIN ||
            after.wallCoverage >= before.wallCoverage + MIN_GLOBAL_COMPONENT_GAIN ||
            after.wallPrecision >= before.wallPrecision + MIN_GLOBAL_COMPONENT_GAIN ||
            after.endpointSupport >= before.endpointSupport + MIN_GLOBAL_ENDPOINT_GAIN
    }

    private fun rawHwc(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = FloatArray(pixels.size * 3)
        var output = 0
        pixels.forEach { color ->
            result[output++] = ((color ushr 16) and 0xff).toFloat()
            result[output++] = ((color ushr 8) and 0xff).toFloat()
            result[output++] = (color and 0xff).toFloat()
        }
        return result
    }

    private fun normalizedPaddedNchw(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = SAM_LONG_SIDE * SAM_LONG_SIDE
        val result = FloatArray(plane * 3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = pixels[y * bitmap.width + x]
                val index = y * SAM_LONG_SIDE + x
                result[index] = (((color ushr 16) and 0xff) - SAM_PIXEL_MEAN_R) / SAM_PIXEL_STD_R
                result[plane + index] = (((color ushr 8) and 0xff) - SAM_PIXEL_MEAN_G) / SAM_PIXEL_STD_G
                result[plane * 2 + index] = ((color and 0xff) - SAM_PIXEL_MEAN_B) / SAM_PIXEL_STD_B
            }
        }
        return result
    }

    private fun quantile(values: List<Float>, quantile: Float): Float {
        if (values.isEmpty()) return 0f
        val index = ((values.lastIndex) * quantile.coerceIn(0f, 1f)).toInt()
        return values[index]
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

    private fun wallLength(wall: WallSegment): Float = distance(wall.start, wall.end)

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        private const val REFINEMENT_MAX_SIDE = 1800
        private const val SAM_LONG_SIDE = 1024
        private const val SAM_MASK_INPUT_SIDE = 256
        private const val MAX_REFINED_WALLS = 48
        private const val MIN_PIXELS_PER_METER = 8f
        private const val MIN_PROMPT_HALF_WIDTH_PX = 7f
        private const val PROMPT_THICKNESS_MULTIPLIER = 1.45f
        private const val PROMPT_CLEARANCE_PX = 5f
        private const val MIN_PROMPT_SPAN_PX = 10f
        private const val MIN_WALL_CONTEXT_PX = 12f
        private const val MIN_MASK_CORRIDOR_PX = 7f
        private const val MASK_CORRIDOR_THICKNESS_MULTIPLIER = 1.6f
        private const val MASK_CORRIDOR_CLEARANCE_PX = 5f
        private const val ENDPOINT_SEARCH_EXTENSION_PX = 16f
        private const val MASK_PIXEL_STEP = 2
        private const val MIN_MASK_SAMPLES = 24
        private const val SAM_MASK_THRESHOLD = 0f
        private const val ALONG_LOW_QUANTILE = 0.025f
        private const val ALONG_HIGH_QUANTILE = 0.975f
        private const val NORMAL_LOW_QUANTILE = 0.08f
        private const val NORMAL_HIGH_QUANTILE = 0.92f
        private const val MIN_RETAINED_WALL_RATIO = 0.72f
        private const val MIN_WALL_THICKNESS_METERS = 0.08f
        private const val MAX_WALL_THICKNESS_METERS = 0.45f
        private const val MIN_REFINED_WALL_METERS = 0.28f
        private const val MAX_ENDPOINT_SHIFT_METERS = 0.18f
        private const val MIN_THICKNESS_RATIO = 0.52f
        private const val MAX_THICKNESS_RATIO = 1.85f
        private const val MOBILE_SAM_REFINEMENT_CONFIDENCE = 0.88f
        private const val MAX_GLOBAL_COVERAGE_LOSS = 0.004f
        private const val MAX_GLOBAL_PRECISION_LOSS = 0.004f
        private const val MAX_GLOBAL_ENDPOINT_LOSS = 0.025f
        private const val MAX_GLOBAL_SCORE_LOSS = 0.0015f
        private const val MIN_GLOBAL_SCORE_GAIN = 0.0008f
        private const val MIN_GLOBAL_COMPONENT_GAIN = 0.002f
        private const val MIN_GLOBAL_ENDPOINT_GAIN = 0.012f
        private const val SAM_PIXEL_MEAN_R = 123.675f
        private const val SAM_PIXEL_MEAN_G = 116.28f
        private const val SAM_PIXEL_MEAN_B = 103.53f
        private const val SAM_PIXEL_STD_R = 58.395f
        private const val SAM_PIXEL_STD_G = 57.12f
        private const val SAM_PIXEL_STD_B = 57.375f
    }
}
