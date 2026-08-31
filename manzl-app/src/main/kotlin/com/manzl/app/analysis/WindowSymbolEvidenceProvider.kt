package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.DoorOpening
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
 * Offline window-symbol evidence provider.
 *
 * Architectural windows are commonly drawn as two thin parallel strokes inside a gap in the main
 * wall line. Candidates come from measured geometry, then the raster is sampled through the shared
 * structural-bounds transform. Page margins/crop differences therefore cannot move the sampling
 * window away from the geometrically accepted opening.
 */
internal class WindowSymbolEvidenceProvider : SemanticEvidenceProvider {

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> =
        withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 32 || height < 32 || structuralPlan.walls.size < 2) return@withContext emptyList()
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            detectFromPixels(pixels, width, height, structuralPlan)
        }

    internal fun detectFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        structuralPlan: FloorPlan,
    ): List<SemanticEvidence> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val candidates = openingGaps(structuralPlan.walls)
        if (candidates.isEmpty()) return emptyList()

        return buildList {
            for (candidate in candidates) {
                if (overlapsKnownDoor(candidate, structuralPlan.doors)) continue
                val score = symbolScore(
                    pixels = pixels,
                    imageWidth = width,
                    imageHeight = height,
                    plan = structuralPlan,
                    gap = candidate,
                ) ?: continue
                if (score < MIN_WINDOW_SYMBOL_CONFIDENCE) continue
                add(
                    SemanticEvidence(
                        kind = SemanticKind.WINDOW,
                        center = candidate.center,
                        widthMeters = candidate.widthMeters,
                        rotationDegrees = candidate.rotationDegrees,
                        confidence = score,
                        source = EvidenceSource.CLASSICAL_CV,
                    )
                )
            }
        }.deduplicateWindows()
    }

    private fun openingGaps(walls: List<WallSegment>): List<OpeningGap> = buildList {
        val horizontal = walls.filter(::isHorizontal)
            .groupBy { quantize((it.start.z + it.end.z) * 0.5f, LINE_BUCKET_METERS) }
        for (group in horizontal.values) {
            val sorted = group.sortedBy { min(it.start.x, it.end.x) }
            for (index in 0 until sorted.lastIndex) {
                val left = sorted[index]
                val right = sorted[index + 1]
                val leftEnd = max(left.start.x, left.end.x)
                val rightStart = min(right.start.x, right.end.x)
                val gap = rightStart - leftEnd
                if (gap !in MIN_WINDOW_WIDTH_METERS..MAX_WINDOW_WIDTH_METERS) continue
                val zA = (left.start.z + left.end.z) * 0.5f
                val zB = (right.start.z + right.end.z) * 0.5f
                if (abs(zA - zB) > COLLINEAR_TOLERANCE_METERS) continue
                add(
                    OpeningGap(
                        center = Vec2((leftEnd + rightStart) * 0.5f, (zA + zB) * 0.5f),
                        widthMeters = gap,
                        rotationDegrees = 0f,
                    )
                )
            }
        }

        val vertical = walls.filter(::isVertical)
            .groupBy { quantize((it.start.x + it.end.x) * 0.5f, LINE_BUCKET_METERS) }
        for (group in vertical.values) {
            val sorted = group.sortedBy { min(it.start.z, it.end.z) }
            for (index in 0 until sorted.lastIndex) {
                val top = sorted[index]
                val bottom = sorted[index + 1]
                val topEnd = max(top.start.z, top.end.z)
                val bottomStart = min(bottom.start.z, bottom.end.z)
                val gap = bottomStart - topEnd
                if (gap !in MIN_WINDOW_WIDTH_METERS..MAX_WINDOW_WIDTH_METERS) continue
                val xA = (top.start.x + top.end.x) * 0.5f
                val xB = (bottom.start.x + bottom.end.x) * 0.5f
                if (abs(xA - xB) > COLLINEAR_TOLERANCE_METERS) continue
                add(
                    OpeningGap(
                        center = Vec2((xA + xB) * 0.5f, (topEnd + bottomStart) * 0.5f),
                        widthMeters = gap,
                        rotationDegrees = 90f,
                    )
                )
            }
        }
    }

    private fun symbolScore(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        plan: FloorPlan,
        gap: OpeningGap,
    ): Float? {
        if (plan.widthMeters <= 0f || plan.depthMeters <= 0f) return null
        val transform = PlanRasterTransform.forImage(plan, imageWidth, imageHeight)
        val center = transform.planToImage(gap.center)
        val centerX = center.first
        val centerY = center.second
        val horizontal = gap.rotationDegrees < 45f || gap.rotationDegrees > 135f
        val alongPixelsPerMeter = if (horizontal) transform.pixelsPerMeterX else transform.pixelsPerMeterZ
        val perpendicularPixelsPerMeter = if (horizontal) transform.pixelsPerMeterZ else transform.pixelsPerMeterX
        val halfAlong = (gap.widthMeters * alongPixelsPerMeter * WINDOW_SCAN_SPAN_FRACTION * 0.5f)
            .roundToInt()
            .coerceAtLeast(MIN_ALONG_HALF_SPAN_PX)
        val perpendicularRadius = (PERPENDICULAR_SCAN_METERS * perpendicularPixelsPerMeter)
            .roundToInt()
            .coerceIn(MIN_PERPENDICULAR_RADIUS_PX, MAX_PERPENDICULAR_RADIUS_PX)

        val bands = if (horizontal) {
            scanHorizontalBands(
                pixels,
                imageWidth,
                imageHeight,
                centerX.roundToInt(),
                centerY.roundToInt(),
                halfAlong,
                perpendicularRadius,
            )
        } else {
            scanVerticalBands(
                pixels,
                imageWidth,
                imageHeight,
                centerX.roundToInt(),
                centerY.roundToInt(),
                halfAlong,
                perpendicularRadius,
            )
        }
        if (bands.size < 2) return null

        var bestScore = 0f
        for (i in 0 until bands.lastIndex) {
            for (j in i + 1 until bands.size) {
                val separationPixels = abs(bands[j].position - bands[i].position)
                val separationMeters = separationPixels / perpendicularPixelsPerMeter
                if (separationMeters !in MIN_WINDOW_LINE_SEPARATION_METERS..MAX_WINDOW_LINE_SEPARATION_METERS) continue
                val density = (bands[i].density + bands[j].density) * 0.5f
                val separationIdeal = 1f - (
                    abs(separationMeters - IDEAL_WINDOW_LINE_SEPARATION_METERS) /
                        MAX_WINDOW_LINE_SEPARATION_METERS
                    ).coerceIn(0f, 1f)
                val score = (density * 0.76f + separationIdeal * 0.24f).coerceIn(0f, 0.93f)
                if (score > bestScore) bestScore = score
            }
        }
        return bestScore.takeIf { it >= MIN_WINDOW_SYMBOL_CONFIDENCE }
    }

    private fun scanHorizontalBands(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        halfAlong: Int,
        radius: Int,
    ): List<InkBand> {
        val raw = ArrayList<InkBand>()
        for (y in max(0, centerY - radius)..min(height - 1, centerY + radius)) {
            val density = lineDensityHorizontal(pixels, width, height, y, centerX - halfAlong, centerX + halfAlong)
            if (density >= MIN_LINE_DENSITY) raw += InkBand(y, density)
        }
        return collapseBands(raw)
    }

    private fun scanVerticalBands(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        halfAlong: Int,
        radius: Int,
    ): List<InkBand> {
        val raw = ArrayList<InkBand>()
        for (x in max(0, centerX - radius)..min(width - 1, centerX + radius)) {
            val density = lineDensityVertical(pixels, width, height, x, centerY - halfAlong, centerY + halfAlong)
            if (density >= MIN_LINE_DENSITY) raw += InkBand(x, density)
        }
        return collapseBands(raw)
    }

    private fun lineDensityHorizontal(
        pixels: IntArray,
        width: Int,
        height: Int,
        y: Int,
        fromX: Int,
        toX: Int,
    ): Float {
        if (y !in 0 until height) return 0f
        val start = fromX.coerceIn(0, width - 1)
        val end = toX.coerceIn(0, width - 1)
        if (end <= start) return 0f
        var ink = 0
        var count = 0
        for (x in start..end) {
            if (isArchitecturalInk(pixels[y * width + x])) ink++
            count++
        }
        return if (count == 0) 0f else ink.toFloat() / count
    }

    private fun lineDensityVertical(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        fromY: Int,
        toY: Int,
    ): Float {
        if (x !in 0 until width) return 0f
        val start = fromY.coerceIn(0, height - 1)
        val end = toY.coerceIn(0, height - 1)
        if (end <= start) return 0f
        var ink = 0
        var count = 0
        for (y in start..end) {
            if (isArchitecturalInk(pixels[y * width + x])) ink++
            count++
        }
        return if (count == 0) 0f else ink.toFloat() / count
    }

    private fun collapseBands(raw: List<InkBand>): List<InkBand> {
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<InkBand>()
        var weightedPosition = 0f
        var weight = 0f
        var maxDensity = 0f
        var previous = raw.first().position - 1

        fun flush() {
            if (weight <= 0f) return
            result += InkBand((weightedPosition / weight).roundToInt(), maxDensity)
            weightedPosition = 0f
            weight = 0f
            maxDensity = 0f
        }

        for (band in raw) {
            if (band.position > previous + 1) flush()
            weightedPosition += band.position * band.density
            weight += band.density
            maxDensity = max(maxDensity, band.density)
            previous = band.position
        }
        flush()
        return result
    }

    private fun overlapsKnownDoor(gap: OpeningGap, doors: List<DoorOpening>): Boolean =
        doors.any { door ->
            val dx = door.center.x - gap.center.x
            val dz = door.center.z - gap.center.z
            val distance = sqrt(dx * dx + dz * dz)
            val overlapRadius = (door.widthMeters + gap.widthMeters) * 0.32f + DOOR_EXCLUSION_MARGIN_METERS
            distance <= overlapRadius
        }

    private fun List<SemanticEvidence>.deduplicateWindows(): List<SemanticEvidence> {
        val result = ArrayList<SemanticEvidence>()
        for (candidate in sortedByDescending { it.confidence }) {
            val duplicate = result.any { existing ->
                val dx = existing.center.x - candidate.center.x
                val dz = existing.center.z - candidate.center.z
                dx * dx + dz * dz <= DUPLICATE_WINDOW_RADIUS_METERS * DUPLICATE_WINDOW_RADIUS_METERS
            }
            if (!duplicate) result += candidate
        }
        return result
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

    private fun isArchitecturalInk(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        val luminance = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val chroma = maxChannel - minChannel
        val blueInk = b >= 90 && b > r * 1.15f && b > g * 1.02f && chroma >= 30
        return luminance <= 145f || blueInk
    }

    private fun quantize(value: Float, step: Float): Int = (value / step).roundToInt()

    private data class OpeningGap(
        val center: Vec2,
        val widthMeters: Float,
        val rotationDegrees: Float,
    )

    private data class InkBand(
        val position: Int,
        val density: Float,
    )

    companion object {
        private const val MIN_WINDOW_WIDTH_METERS = 0.45f
        private const val MAX_WINDOW_WIDTH_METERS = 4.20f
        private const val MIN_CONTEXT_WALL_METERS = 0.45f
        private const val AXIS_TOLERANCE_METERS = 0.07f
        private const val COLLINEAR_TOLERANCE_METERS = 0.16f
        private const val LINE_BUCKET_METERS = 0.14f
        private const val WINDOW_SCAN_SPAN_FRACTION = 0.70f
        private const val PERPENDICULAR_SCAN_METERS = 0.32f
        private const val MIN_PERPENDICULAR_RADIUS_PX = 5
        private const val MAX_PERPENDICULAR_RADIUS_PX = 34
        private const val MIN_ALONG_HALF_SPAN_PX = 8
        private const val MIN_LINE_DENSITY = 0.56f
        private const val MIN_WINDOW_LINE_SEPARATION_METERS = 0.035f
        private const val MAX_WINDOW_LINE_SEPARATION_METERS = 0.28f
        private const val IDEAL_WINDOW_LINE_SEPARATION_METERS = 0.11f
        private const val MIN_WINDOW_SYMBOL_CONFIDENCE = 0.66f
        private const val DOOR_EXCLUSION_MARGIN_METERS = 0.16f
        private const val DUPLICATE_WINDOW_RADIUS_METERS = 0.32f
    }
}
