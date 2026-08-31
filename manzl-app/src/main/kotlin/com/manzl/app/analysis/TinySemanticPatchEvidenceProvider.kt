package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tiny bundled neural semantic provider.
 *
 * The model is deliberately narrow: it classifies small raster patches around geometry/CV proposals
 * as OTHER / DOOR / WINDOW / STAIR. It never scans the image and invents topology by itself. Door
 * and window proposals must originate from measured collinear wall gaps; stair proposals come from
 * the deterministic repeated-tread detector. Accepted neural observations still pass through
 * SemanticEvidenceConsensus and GeometryEvidenceFusion before entering FloorPlan.
 *
 * The weights are embedded in TinySemanticPatchModel and were trained only on procedurally generated
 * symbols, so this layer has no runtime network dependency and no third-party training-image license.
 */
internal class TinySemanticPatchEvidenceProvider(
    private val stairProposalProvider: SemanticEvidenceProvider = StairPatternEvidenceProvider(),
) : SemanticEvidenceProvider {

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> =
        withContext(Dispatchers.Default) {
            if (
                bitmap.width < MIN_IMAGE_SIDE ||
                bitmap.height < MIN_IMAGE_SIDE ||
                structuralPlan.walls.size < 2 ||
                structuralPlan.widthMeters <= 0f ||
                structuralPlan.depthMeters <= 0f
            ) {
                return@withContext emptyList()
            }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val transform = PlanRasterTransform.forImage(structuralPlan, bitmap.width, bitmap.height)
            val evidence = ArrayList<SemanticEvidence>()

            openingGapCandidates(structuralPlan.walls)
                .take(MAX_OPENING_CANDIDATES)
                .forEach { candidate ->
                    val patchSideMeters = (
                        max(MIN_OPENING_PATCH_METERS, candidate.widthMeters * OPENING_PATCH_WIDTH_MULTIPLIER)
                        ).coerceAtMost(MAX_OPENING_PATCH_METERS)
                    val patch = extractPatch(
                        pixels = pixels,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        transform = transform,
                        center = candidate.center,
                        sideMeters = patchSideMeters,
                    )
                    val prediction = TinySemanticPatchModel.predict(patch)
                    if (!prediction.isStrong()) return@forEach

                    when (prediction.label) {
                        TinySemanticPatchModel.PatchClass.DOOR -> {
                            if (candidate.widthMeters in MIN_DOOR_WIDTH_METERS..MAX_DOOR_WIDTH_METERS) {
                                evidence += SemanticEvidence(
                                    kind = SemanticKind.DOOR,
                                    center = candidate.center,
                                    widthMeters = candidate.widthMeters,
                                    rotationDegrees = candidate.rotationDegrees,
                                    confidence = neuralEvidenceConfidence(prediction),
                                    source = EvidenceSource.LOCAL_AI,
                                )
                            }
                        }

                        TinySemanticPatchModel.PatchClass.WINDOW -> {
                            if (candidate.widthMeters in MIN_WINDOW_WIDTH_METERS..MAX_WINDOW_WIDTH_METERS) {
                                evidence += SemanticEvidence(
                                    kind = SemanticKind.WINDOW,
                                    center = candidate.center,
                                    widthMeters = candidate.widthMeters,
                                    rotationDegrees = candidate.rotationDegrees,
                                    confidence = neuralEvidenceConfidence(prediction),
                                    source = EvidenceSource.LOCAL_AI,
                                )
                            }
                        }

                        TinySemanticPatchModel.PatchClass.OTHER,
                        TinySemanticPatchModel.PatchClass.STAIR,
                        -> Unit
                    }
                }

            // Reuse deterministic stair proposals only as regions of interest. The neural model has
            // to independently agree that the local raster actually resembles a staircase before a
            // LOCAL_AI observation is emitted.
            val stairProposals = runCatching {
                stairProposalProvider.analyze(bitmap, structuralPlan)
            }.getOrDefault(emptyList())
                .filter { it.kind == SemanticKind.STAIR }
                .take(MAX_STAIR_CANDIDATES)

            stairProposals.forEach { proposal ->
                val widthMeters = proposal.widthMeters ?: return@forEach
                val runMeters = proposal.lengthMeters ?: return@forEach
                val patchSideMeters = (
                    max(widthMeters, runMeters) * STAIR_PATCH_SIZE_MULTIPLIER
                    ).coerceIn(MIN_STAIR_PATCH_METERS, MAX_STAIR_PATCH_METERS)
                val patch = extractPatch(
                    pixels = pixels,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    transform = transform,
                    center = proposal.center,
                    sideMeters = patchSideMeters,
                )
                val prediction = TinySemanticPatchModel.predict(patch)
                if (
                    prediction.label != TinySemanticPatchModel.PatchClass.STAIR ||
                    !prediction.isStrong(STAIR_MIN_CONFIDENCE, STAIR_MIN_MARGIN)
                ) {
                    return@forEach
                }
                evidence += proposal.copy(
                    confidence = neuralEvidenceConfidence(prediction),
                    source = EvidenceSource.LOCAL_AI,
                )
            }

            evidence.deduplicate()
        }

    private fun TinySemanticPatchModel.Prediction.isStrong(
        minimumConfidence: Float = MIN_NEURAL_CONFIDENCE,
        minimumMargin: Float = MIN_NEURAL_MARGIN,
    ): Boolean = confidence >= minimumConfidence && margin >= minimumMargin

    private fun neuralEvidenceConfidence(prediction: TinySemanticPatchModel.Prediction): Float =
        (
            prediction.confidence * 0.82f +
                prediction.margin * 0.18f
            ).coerceIn(MIN_REPORTED_CONFIDENCE, MAX_REPORTED_CONFIDENCE)

    private fun extractPatch(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        transform: PlanRasterTransform,
        center: Vec2,
        sideMeters: Float,
    ): FloatArray {
        val output = FloatArray(PATCH_SIZE * PATCH_SIZE)
        val half = sideMeters * 0.5f
        var index = 0
        for (py in 0 until PATCH_SIZE) {
            val vz = ((py + 0.5f) / PATCH_SIZE.toFloat() - 0.5f) * sideMeters
            for (px in 0 until PATCH_SIZE) {
                val vx = ((px + 0.5f) / PATCH_SIZE.toFloat() - 0.5f) * sideMeters
                val imagePoint = transform.planToImage(
                    Vec2(
                        x = center.x + vx.coerceIn(-half, half),
                        z = center.z + vz.coerceIn(-half, half),
                    )
                )
                val x = imagePoint.first.roundToInt()
                val y = imagePoint.second.roundToInt()
                output[index++] = if (x in 0 until imageWidth && y in 0 until imageHeight) {
                    inkStrength(pixels[y * imageWidth + x])
                } else {
                    0f
                }
            }
        }
        return output
    }

    private fun inkStrength(color: Int): Float {
        val r = ((color ushr 16) and 0xFF).toFloat()
        val g = ((color ushr 8) and 0xFF).toFloat()
        val b = (color and 0xFF).toFloat()
        val luminance = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val dark = ((188f - luminance) / 155f).coerceIn(0f, 1f)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val chroma = maxChannel - minChannel
        val blueprint = if (b > r * 1.12f && b > g * 1.02f && chroma > 24f) {
            ((chroma - 24f) / 105f).coerceIn(0f, 1f)
        } else {
            0f
        }
        return max(dark, blueprint)
    }

    private fun openingGapCandidates(walls: List<WallSegment>): List<OpeningCandidate> {
        val raw = ArrayList<OpeningCandidate>()

        walls.filter(::isHorizontal)
            .groupBy { quantize((it.start.z + it.end.z) * 0.5f, LINE_BUCKET_METERS) }
            .values
            .forEach { group ->
                val sorted = group.sortedBy { min(it.start.x, it.end.x) }
                for (index in 0 until sorted.lastIndex) {
                    val left = sorted[index]
                    val right = sorted[index + 1]
                    val leftEnd = max(left.start.x, left.end.x)
                    val rightStart = min(right.start.x, right.end.x)
                    val gap = rightStart - leftEnd
                    if (gap !in MIN_OPENING_WIDTH_METERS..MAX_OPENING_WIDTH_METERS) continue
                    val zA = (left.start.z + left.end.z) * 0.5f
                    val zB = (right.start.z + right.end.z) * 0.5f
                    if (abs(zA - zB) > COLLINEAR_TOLERANCE_METERS) continue
                    raw += OpeningCandidate(
                        center = Vec2((leftEnd + rightStart) * 0.5f, (zA + zB) * 0.5f),
                        widthMeters = gap,
                        rotationDegrees = 0f,
                    )
                }
            }

        walls.filter(::isVertical)
            .groupBy { quantize((it.start.x + it.end.x) * 0.5f, LINE_BUCKET_METERS) }
            .values
            .forEach { group ->
                val sorted = group.sortedBy { min(it.start.z, it.end.z) }
                for (index in 0 until sorted.lastIndex) {
                    val top = sorted[index]
                    val bottom = sorted[index + 1]
                    val topEnd = max(top.start.z, top.end.z)
                    val bottomStart = min(bottom.start.z, bottom.end.z)
                    val gap = bottomStart - topEnd
                    if (gap !in MIN_OPENING_WIDTH_METERS..MAX_OPENING_WIDTH_METERS) continue
                    val xA = (top.start.x + top.end.x) * 0.5f
                    val xB = (bottom.start.x + bottom.end.x) * 0.5f
                    if (abs(xA - xB) > COLLINEAR_TOLERANCE_METERS) continue
                    raw += OpeningCandidate(
                        center = Vec2((xA + xB) * 0.5f, (topEnd + bottomStart) * 0.5f),
                        widthMeters = gap,
                        rotationDegrees = 90f,
                    )
                }
            }

        return raw.sortedBy { it.center.x * it.center.x + it.center.z * it.center.z }
            .fold(ArrayList()) { accepted, candidate ->
                val duplicate = accepted.any { existing ->
                    val dx = existing.center.x - candidate.center.x
                    val dz = existing.center.z - candidate.center.z
                    sqrt(dx * dx + dz * dz) <= OPENING_DUPLICATE_RADIUS_METERS
                }
                if (!duplicate) accepted += candidate
                accepted
            }
    }

    private fun List<SemanticEvidence>.deduplicate(): List<SemanticEvidence> {
        val accepted = ArrayList<SemanticEvidence>()
        for (candidate in sortedByDescending { it.confidence }) {
            val duplicate = accepted.any { existing ->
                if (existing.kind != candidate.kind) return@any false
                val dx = existing.center.x - candidate.center.x
                val dz = existing.center.z - candidate.center.z
                val radius = when (candidate.kind) {
                    SemanticKind.STAIR -> 0.55f
                    SemanticKind.DOOR, SemanticKind.WINDOW -> 0.28f
                    SemanticKind.ROOM -> 0f
                }
                dx * dx + dz * dz <= radius * radius
            }
            if (!duplicate) accepted += candidate
        }
        return accepted
    }

    private fun isHorizontal(wall: WallSegment): Boolean {
        val dx = abs(wall.end.x - wall.start.x)
        val dz = abs(wall.end.z - wall.start.z)
        return dx >= MIN_CONTEXT_WALL_METERS && dz <= AXIS_TOLERANCE_METERS
    }

    private fun isVertical(wall: WallSegment): Boolean {
        val dx = abs(wall.end.x - wall.start.x)
        val dz = abs(wall.end.z - wall.start.z)
        return dz >= MIN_CONTEXT_WALL_METERS && dx <= AXIS_TOLERANCE_METERS
    }

    private fun quantize(value: Float, step: Float): Int = (value / step).roundToInt()

    private data class OpeningCandidate(
        val center: Vec2,
        val widthMeters: Float,
        val rotationDegrees: Float,
    )

    companion object {
        private const val PATCH_SIZE = 16
        private const val MIN_IMAGE_SIDE = 48
        private const val MAX_OPENING_CANDIDATES = 48
        private const val MAX_STAIR_CANDIDATES = 6
        private const val MIN_CONTEXT_WALL_METERS = 0.42f
        private const val AXIS_TOLERANCE_METERS = 0.08f
        private const val COLLINEAR_TOLERANCE_METERS = 0.17f
        private const val LINE_BUCKET_METERS = 0.14f
        private const val MIN_OPENING_WIDTH_METERS = 0.45f
        private const val MAX_OPENING_WIDTH_METERS = 4.20f
        private const val MIN_DOOR_WIDTH_METERS = 0.62f
        private const val MAX_DOOR_WIDTH_METERS = 1.68f
        private const val MIN_WINDOW_WIDTH_METERS = 0.45f
        private const val MAX_WINDOW_WIDTH_METERS = 4.20f
        private const val OPENING_DUPLICATE_RADIUS_METERS = 0.26f
        private const val MIN_OPENING_PATCH_METERS = 1.20f
        private const val MAX_OPENING_PATCH_METERS = 4.40f
        private const val OPENING_PATCH_WIDTH_MULTIPLIER = 1.55f
        private const val STAIR_PATCH_SIZE_MULTIPLIER = 1.18f
        private const val MIN_STAIR_PATCH_METERS = 1.60f
        private const val MAX_STAIR_PATCH_METERS = 8.20f
        private const val MIN_NEURAL_CONFIDENCE = 0.82f
        private const val MIN_NEURAL_MARGIN = 0.22f
        private const val STAIR_MIN_CONFIDENCE = 0.86f
        private const val STAIR_MIN_MARGIN = 0.28f
        private const val MIN_REPORTED_CONFIDENCE = 0.70f
        private const val MAX_REPORTED_CONFIDENCE = 0.94f
    }
}
