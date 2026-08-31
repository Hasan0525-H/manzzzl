package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import com.manzl.app.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline staircase evidence from repeated parallel tread marks.
 *
 * This is deliberately deterministic computer vision rather than a black-box model. A staircase is
 * suggested only when at least five similarly sized, regularly spaced strokes form a compact band.
 * Raster coordinates are converted through the same structural content bounds used by wall geometry,
 * so asymmetric white margins cannot shift the stair into another room.
 */
internal class StairPatternEvidenceProvider : SemanticEvidenceProvider {

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> =
        withContext(Dispatchers.Default) {
            if (bitmap.width < 40 || bitmap.height < 40) return@withContext emptyList()
            val working = bitmap.downscale(MAX_SIDE)
            val pixels = IntArray(working.width * working.height)
            working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
            val mask = BooleanArray(pixels.size) { index -> isStroke(pixels[index]) }
            val transform = PlanRasterTransform.forImage(structuralPlan, working.width, working.height)

            StairPatternDetector.detect(mask, working.width, working.height).mapNotNull { candidate ->
                val center = transform.imageToPlan(candidate.centerX, candidate.centerY)

                val stairWidth: Float
                val stairRun: Float
                val rotation: Float
                if (candidate.treadsHorizontal) {
                    stairWidth = candidate.treadLengthPx / transform.pixelsPerMeterX
                    stairRun = candidate.bandLengthPx / transform.pixelsPerMeterZ
                    rotation = 90f
                } else {
                    stairWidth = candidate.treadLengthPx / transform.pixelsPerMeterZ
                    stairRun = candidate.bandLengthPx / transform.pixelsPerMeterX
                    rotation = 0f
                }

                if (stairWidth !in MIN_EVIDENCE_WIDTH_METERS..MAX_EVIDENCE_WIDTH_METERS) return@mapNotNull null
                if (stairRun !in MIN_EVIDENCE_RUN_METERS..MAX_EVIDENCE_RUN_METERS) return@mapNotNull null

                SemanticEvidence(
                    kind = SemanticKind.STAIR,
                    center = center,
                    widthMeters = stairWidth,
                    lengthMeters = stairRun,
                    rotationDegrees = rotation,
                    confidence = candidate.confidence,
                    source = EvidenceSource.CLASSICAL_CV,
                )
            }
        }

    private fun Bitmap.downscale(maxSide: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            max(1, (width * ratio).toInt()),
            max(1, (height * ratio).toInt()),
            true,
        )
    }

    private fun isStroke(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val luminance = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val chroma = maxChannel - minChannel
        val dark = luminance <= 125f && chroma <= 72
        val blue = b >= 100 && b > r * 1.14f && b > g * 1.02f && chroma >= 32
        return dark || blue
    }

    companion object {
        private const val MAX_SIDE = 1000
        private const val MIN_EVIDENCE_WIDTH_METERS = 0.70f
        private const val MAX_EVIDENCE_WIDTH_METERS = 2.75f
        private const val MIN_EVIDENCE_RUN_METERS = 1.45f
        private const val MAX_EVIDENCE_RUN_METERS = 8.80f
    }
}

internal data class StairPatternCandidate(
    val centerX: Float,
    val centerY: Float,
    val treadLengthPx: Float,
    val bandLengthPx: Float,
    val treadsHorizontal: Boolean,
    val confidence: Float,
)

/** Pure Boolean-mask detector so the hard geometry can be regression-tested without Android APIs. */
internal object StairPatternDetector {

    fun detect(mask: BooleanArray, width: Int, height: Int): List<StairPatternCandidate> {
        if (width <= 0 || height <= 0 || mask.size != width * height) return emptyList()
        val horizontal = scan(mask, width, height, horizontal = true)
        val vertical = scan(mask, width, height, horizontal = false)
        val raw = buildList {
            addAll(group(horizontal, width, height, horizontal = true))
            addAll(group(vertical, width, height, horizontal = false))
        }.sortedByDescending { it.confidence }

        val accepted = ArrayList<StairPatternCandidate>()
        for (candidate in raw) {
            val duplicate = accepted.any { existing ->
                val dx = (existing.centerX - candidate.centerX) / width.toFloat()
                val dy = (existing.centerY - candidate.centerY) / height.toFloat()
                dx * dx + dy * dy < DUPLICATE_CENTER_FRACTION_SQ
            }
            if (!duplicate) accepted += candidate
            if (accepted.size >= MAX_CANDIDATES) break
        }
        return accepted
    }

    private fun scan(
        mask: BooleanArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): List<Run> {
        val across = if (horizontal) width else height
        val along = if (horizontal) height else width
        val minRun = max(8, min(width, height) / 90)
        val maxRun = max(minRun + 2, (min(width, height) * 0.50f).toInt())
        val result = ArrayList<Run>()

        for (fixed in 1 until along - 1) {
            var start = -1
            for (moving in 1 until across - 1) {
                val index = if (horizontal) fixed * width + moving else moving * width + fixed
                val solid = mask[index]
                if (solid && start < 0) start = moving
                if ((!solid || moving == across - 2) && start >= 0) {
                    val end = if (solid) moving else moving - 1
                    val length = end - start + 1
                    if (length in minRun..maxRun) result += Run(fixed, start, end)
                    start = -1
                }
            }
        }
        return result
    }

    private fun group(
        runs: List<Run>,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): List<StairPatternCandidate> {
        if (runs.size < MIN_TREADS) return emptyList()
        val alongPixels = if (horizontal) height else width
        val maxBand = max(18, (alongPixels * MAX_BAND_FRACTION).toInt())
        val fixedMerge = max(1, alongPixels / 320)
        val result = ArrayList<StairPatternCandidate>()

        for (seedIndex in runs.indices step 2) {
            val seed = runs[seedIndex]
            val seedLength = seed.length.toFloat()
            val compatible = runs.filter { run ->
                abs(run.fixed - seed.fixed) <= maxBand &&
                    run.length / seedLength in MIN_LENGTH_RATIO..MAX_LENGTH_RATIO &&
                    overlapRatio(seed, run) >= MIN_SPAN_OVERLAP
            }
            if (compatible.size < MIN_TREADS) continue

            val clustered = clusterThickness(compatible, fixedMerge)
            if (clustered.size !in MIN_TREADS..MAX_TREADS) continue

            val fixedValues = clustered.map { it.fixed.toFloat() }.sorted()
            val deltas = fixedValues.zipWithNext { a, b -> b - a }.filter { it > fixedMerge * 0.7f }
            if (deltas.size < MIN_TREADS - 1) continue
            val medianSpacing = median(deltas)
            val minSpacing = max(2f, alongPixels / 420f)
            val maxSpacing = max(minSpacing + 1f, alongPixels / 18f)
            if (medianSpacing !in minSpacing..maxSpacing) continue

            val spacingError = deltas.sumOf { delta ->
                (abs(delta - medianSpacing) / max(1f, medianSpacing)).toDouble()
            }.toFloat() / deltas.size
            if (spacingError > MAX_MEAN_SPACING_ERROR) continue

            val lengths = clustered.map { it.length.toFloat() }
            val medianLength = median(lengths)
            val lengthError = lengths.sumOf { value ->
                (abs(value - medianLength) / max(1f, medianLength)).toDouble()
            }.toFloat() / lengths.size
            if (lengthError > MAX_MEAN_LENGTH_ERROR) continue

            val minFixed = clustered.minOf { it.fixed }.toFloat()
            val maxFixed = clustered.maxOf { it.fixed }.toFloat()
            val bandLength = (maxFixed - minFixed + medianSpacing).coerceAtLeast(1f)
            if (bandLength < medianLength * MIN_RUN_TO_WIDTH_RATIO) continue

            val spanFrom = clustered.map { it.from }.sorted()[clustered.size / 2]
            val spanTo = clustered.map { it.to }.sorted()[clustered.size / 2]
            val acrossCenter = (spanFrom + spanTo) * 0.5f
            val alongCenter = (minFixed + maxFixed) * 0.5f

            val countScore = ((clustered.size - MIN_TREADS).toFloat() / 8f).coerceIn(0f, 1f)
            val regularityScore = (1f - spacingError / MAX_MEAN_SPACING_ERROR).coerceIn(0f, 1f)
            val consistencyScore = (1f - lengthError / MAX_MEAN_LENGTH_ERROR).coerceIn(0f, 1f)
            val confidence = (
                0.66f + countScore * 0.12f + regularityScore * 0.10f + consistencyScore * 0.08f
                ).coerceIn(0f, 0.94f)

            result += if (horizontal) {
                StairPatternCandidate(
                    centerX = acrossCenter,
                    centerY = alongCenter,
                    treadLengthPx = medianLength,
                    bandLengthPx = bandLength,
                    treadsHorizontal = true,
                    confidence = confidence,
                )
            } else {
                StairPatternCandidate(
                    centerX = alongCenter,
                    centerY = acrossCenter,
                    treadLengthPx = medianLength,
                    bandLengthPx = bandLength,
                    treadsHorizontal = false,
                    confidence = confidence,
                )
            }
        }
        return result
    }

    private fun clusterThickness(runs: List<Run>, tolerance: Int): List<Run> {
        val sorted = runs.sortedBy { it.fixed }
        val clusters = ArrayList<MutableList<Run>>()
        for (run in sorted) {
            val last = clusters.lastOrNull()
            if (last == null || abs(last.last().fixed - run.fixed) > tolerance) {
                clusters += mutableListOf(run)
            } else {
                last += run
            }
        }
        return clusters.map { cluster ->
            val fixed = cluster.map { it.fixed }.sorted()[cluster.size / 2]
            val from = cluster.map { it.from }.sorted()[cluster.size / 2]
            val to = cluster.map { it.to }.sorted()[cluster.size / 2]
            Run(fixed, from, to)
        }
    }

    private fun overlapRatio(a: Run, b: Run): Float {
        val overlap = max(0, min(a.to, b.to) - max(a.from, b.from) + 1)
        return overlap.toFloat() / min(a.length, b.length).coerceAtLeast(1).toFloat()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0f
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5f else sorted[middle]
    }

    private data class Run(
        val fixed: Int,
        val from: Int,
        val to: Int,
    ) {
        val length: Int get() = to - from + 1
    }

    private const val MIN_TREADS = 5
    private const val MAX_TREADS = 24
    private const val MIN_LENGTH_RATIO = 0.60f
    private const val MAX_LENGTH_RATIO = 1.45f
    private const val MIN_SPAN_OVERLAP = 0.62f
    private const val MAX_BAND_FRACTION = 0.27f
    private const val MAX_MEAN_SPACING_ERROR = 0.34f
    private const val MAX_MEAN_LENGTH_ERROR = 0.26f
    private const val MIN_RUN_TO_WIDTH_RATIO = 0.48f
    private const val DUPLICATE_CENTER_FRACTION_SQ = 0.035f * 0.035f
    private const val MAX_CANDIDATES = 6
}
