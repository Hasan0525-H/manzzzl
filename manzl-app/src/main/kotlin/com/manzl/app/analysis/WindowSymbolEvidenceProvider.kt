package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline window-symbol evidence provider.
 *
 * Architectural windows are commonly drawn as two thin parallel strokes inside a gap in the main
 * wall line. Candidates come only from measured opening-sized gaps, then the raster is sampled in
 * the wall's own local frame through [PlanRasterTransform]. Horizontal, vertical and diagonal walls
 * therefore use the same detector; page margins/crop differences cannot move the sampling window.
 *
 * This provider reports semantics only. It never creates a gap or changes wall geometry. The final
 * opening still has to pass GeometryEvidenceFusion's measured-gap host guard.
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
        if (
            width <= 0 ||
            height <= 0 ||
            pixels.size < width * height ||
            structuralPlan.widthMeters <= 0f ||
            structuralPlan.depthMeters <= 0f
        ) return emptyList()

        val candidates = MeasuredOpeningGapDetector.detect(
            walls = structuralPlan.walls,
            minWidthMeters = MIN_WINDOW_WIDTH_METERS,
            maxWidthMeters = MAX_WINDOW_WIDTH_METERS,
            maxResults = MAX_WINDOW_CANDIDATES,
        )
        if (candidates.isEmpty()) return emptyList()

        val transform = PlanRasterTransform.forImage(structuralPlan, width, height)
        return buildList {
            for (candidate in candidates) {
                if (overlapsKnownDoor(candidate, structuralPlan.doors)) continue
                val score = symbolScore(
                    pixels = pixels,
                    imageWidth = width,
                    imageHeight = height,
                    transform = transform,
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

    /**
     * Samples long line density at normal offsets from the measured wall gap. All sampling happens in
     * metres in the wall-local frame, while sample density is chosen from the projected pixel scale so
     * the same thresholds remain stable on anisotropic scans and arbitrary wall angles.
     */
    private fun symbolScore(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        transform: PlanRasterTransform,
        gap: MeasuredOpeningGapDetector.Gap,
    ): Float? {
        val radians = gap.rotationDegrees * PI.toFloat() / 180f
        val axisX = cos(radians)
        val axisZ = sin(radians)
        val normalX = -axisZ
        val normalZ = axisX

        val alongPixelsPerMeter = directionalPixelsPerMeter(
            axisX,
            axisZ,
            transform.pixelsPerMeterX,
            transform.pixelsPerMeterZ,
        )
        val normalPixelsPerMeter = directionalPixelsPerMeter(
            normalX,
            normalZ,
            transform.pixelsPerMeterX,
            transform.pixelsPerMeterZ,
        )
        if (alongPixelsPerMeter <= 0f || normalPixelsPerMeter <= 0f) return null

        val halfAlongPixels = (
            gap.widthMeters * alongPixelsPerMeter * WINDOW_SCAN_SPAN_FRACTION * 0.5f
            ).roundToInt().coerceIn(MIN_ALONG_HALF_SPAN_PX, MAX_ALONG_HALF_SPAN_PX)
        val normalRadiusPixels = (PERPENDICULAR_SCAN_METERS * normalPixelsPerMeter)
            .roundToInt()
            .coerceIn(MIN_PERPENDICULAR_RADIUS_PX, MAX_PERPENDICULAR_RADIUS_PX)

        val rawBands = ArrayList<MetricInkBand>()
        for (normalPixelOffset in -normalRadiusPixels..normalRadiusPixels) {
            val normalMeters = normalPixelOffset / normalPixelsPerMeter
            val density = localLineDensity(
                pixels = pixels,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                transform = transform,
                centerX = gap.center.x,
                centerZ = gap.center.z,
                axisX = axisX,
                axisZ = axisZ,
                normalX = normalX,
                normalZ = normalZ,
                normalMeters = normalMeters,
                halfAlongPixels = halfAlongPixels,
                alongPixelsPerMeter = alongPixelsPerMeter,
            )
            if (density >= MIN_LINE_DENSITY) {
                rawBands += MetricInkBand(
                    sampleIndex = normalPixelOffset,
                    positionMeters = normalMeters,
                    density = density,
                )
            }
        }

        val bands = collapseBands(rawBands)
        if (bands.size < 2) return null

        var bestScore = 0f
        for (i in 0 until bands.lastIndex) {
            for (j in i + 1 until bands.size) {
                val separationMeters = abs(bands[j].positionMeters - bands[i].positionMeters)
                if (separationMeters !in MIN_WINDOW_LINE_SEPARATION_METERS..MAX_WINDOW_LINE_SEPARATION_METERS) {
                    continue
                }
                val density = (bands[i].density + bands[j].density) * 0.5f
                val separationIdeal = 1f - (
                    abs(separationMeters - IDEAL_WINDOW_LINE_SEPARATION_METERS) /
                        MAX_WINDOW_LINE_SEPARATION_METERS
                    ).coerceIn(0f, 1f)
                val structuralSupport = gap.supportConfidence.coerceIn(0f, 1f)
                val score = (
                    density * 0.72f +
                        separationIdeal * 0.20f +
                        structuralSupport * 0.08f
                    ).coerceIn(0f, 0.94f)
                if (score > bestScore) bestScore = score
            }
        }
        return bestScore.takeIf { it >= MIN_WINDOW_SYMBOL_CONFIDENCE }
    }

    private fun localLineDensity(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        transform: PlanRasterTransform,
        centerX: Float,
        centerZ: Float,
        axisX: Float,
        axisZ: Float,
        normalX: Float,
        normalZ: Float,
        normalMeters: Float,
        halfAlongPixels: Int,
        alongPixelsPerMeter: Float,
    ): Float {
        var ink = 0
        var valid = 0
        for (alongPixelOffset in -halfAlongPixels..halfAlongPixels) {
            val alongMeters = alongPixelOffset / alongPixelsPerMeter
            val planPoint = com.manzl.app.model.Vec2(
                x = centerX + axisX * alongMeters + normalX * normalMeters,
                z = centerZ + axisZ * alongMeters + normalZ * normalMeters,
            )
            val imagePoint = transform.planToImage(planPoint)
            val x = imagePoint.first.roundToInt()
            val y = imagePoint.second.roundToInt()
            if (x !in 0 until imageWidth || y !in 0 until imageHeight) continue
            valid++
            if (isArchitecturalInk(pixels[y * imageWidth + x])) ink++
        }
        return if (valid < MIN_VALID_ALONG_SAMPLES) 0f else ink / valid.toFloat()
    }

    private fun directionalPixelsPerMeter(
        directionX: Float,
        directionZ: Float,
        pixelsPerMeterX: Float,
        pixelsPerMeterZ: Float,
    ): Float {
        val imageX = directionX * pixelsPerMeterX
        val imageY = directionZ * pixelsPerMeterZ
        return sqrt(imageX * imageX + imageY * imageY)
    }

    private fun collapseBands(raw: List<MetricInkBand>): List<CollapsedBand> {
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<CollapsedBand>()
        var weightedPosition = 0f
        var totalWeight = 0f
        var maxDensity = 0f
        var previousSample = raw.first().sampleIndex - 1

        fun flush() {
            if (totalWeight <= 0f) return
            result += CollapsedBand(
                positionMeters = weightedPosition / totalWeight,
                density = maxDensity,
            )
            weightedPosition = 0f
            totalWeight = 0f
            maxDensity = 0f
        }

        for (band in raw) {
            if (band.sampleIndex > previousSample + 1) flush()
            weightedPosition += band.positionMeters * band.density
            totalWeight += band.density
            maxDensity = max(maxDensity, band.density)
            previousSample = band.sampleIndex
        }
        flush()
        return result
    }

    private fun overlapsKnownDoor(
        gap: MeasuredOpeningGapDetector.Gap,
        doors: List<DoorOpening>,
    ): Boolean = doors.any { door ->
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

    private data class MetricInkBand(
        val sampleIndex: Int,
        val positionMeters: Float,
        val density: Float,
    )

    private data class CollapsedBand(
        val positionMeters: Float,
        val density: Float,
    )

    companion object {
        private const val MIN_WINDOW_WIDTH_METERS = 0.45f
        private const val MAX_WINDOW_WIDTH_METERS = 4.20f
        private const val MAX_WINDOW_CANDIDATES = 64
        private const val WINDOW_SCAN_SPAN_FRACTION = 0.70f
        private const val PERPENDICULAR_SCAN_METERS = 0.32f
        private const val MIN_PERPENDICULAR_RADIUS_PX = 5
        private const val MAX_PERPENDICULAR_RADIUS_PX = 34
        private const val MIN_ALONG_HALF_SPAN_PX = 8
        private const val MAX_ALONG_HALF_SPAN_PX = 180
        private const val MIN_VALID_ALONG_SAMPLES = 12
        private const val MIN_LINE_DENSITY = 0.56f
        private const val MIN_WINDOW_LINE_SEPARATION_METERS = 0.035f
        private const val MAX_WINDOW_LINE_SEPARATION_METERS = 0.28f
        private const val IDEAL_WINDOW_LINE_SEPARATION_METERS = 0.11f
        private const val MIN_WINDOW_SYMBOL_CONFIDENCE = 0.66f
        private const val DOOR_EXCLUSION_MARGIN_METERS = 0.16f
        private const val DUPLICATE_WINDOW_RADIUS_METERS = 0.32f
    }
}
