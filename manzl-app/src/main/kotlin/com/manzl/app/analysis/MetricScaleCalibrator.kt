package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ScaleCalibration(
    val longSideMeters: Float,
    val confidence: Float,
    val source: String,
)

internal enum class DimensionAxis {
    HORIZONTAL,
    VERTICAL,
    UNKNOWN,
}

internal data class AxisDimensionEvidence(
    val meters: Float,
    val axis: DimensionAxis,
    val score: Float,
)

/**
 * Reads printed architectural dimensions using a bundled on-device ML Kit OCR model.
 *
 * Strong evidence is axis-aware: a dimension nearest the top/bottom edge is treated as a horizontal
 * dimension and one nearest the left/right edge as vertical. When independent horizontal and vertical
 * dimensions imply the same metres-per-pixel scale, their agreement raises confidence sharply.
 * This avoids the old failure mode where a valid short-side dimension was mistaken for the plan's
 * long side. Weak/inconsistent evidence still falls back conservatively and is surfaced to the user
 * by the explicit metric-scale review flow.
 */
internal object MetricScaleCalibrator {

    suspend fun calibrate(bitmap: Bitmap): ScaleCalibration {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognize(recognizer, InputImage.fromBitmap(bitmap, 0))
            chooseOverallDimension(
                text = result,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            ) ?: fallback()
        } catch (_: Throwable) {
            fallback()
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
    ): Text = suspendCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    private fun chooseOverallDimension(
        text: Text,
        imageWidth: Int,
        imageHeight: Int,
    ): ScaleCalibration? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val evidence = ArrayList<AxisDimensionEvidence>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val meters = parseDimensionMeters(element.text) ?: continue
                    if (meters !in MIN_OVERALL_METERS..MAX_OVERALL_METERS) continue
                    val score = candidateScore(
                        rawText = element.text,
                        meters = meters,
                        box = box,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                    )
                    evidence += AxisDimensionEvidence(
                        meters = meters,
                        axis = inferAxis(box, imageWidth, imageHeight),
                        score = score,
                    )
                }
            }
        }

        return resolveAxisEvidence(evidence, imageWidth, imageHeight)
    }

    internal fun resolveAxisEvidence(
        evidence: List<AxisDimensionEvidence>,
        imageWidth: Int,
        imageHeight: Int,
    ): ScaleCalibration? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val accepted = evidence
            .filter { it.meters in MIN_OVERALL_METERS..MAX_OVERALL_METERS && it.score >= MIN_EVIDENCE_SCORE }
            .sortedByDescending { it.score }
        if (accepted.isEmpty()) return null

        val horizontals = accepted.filter { it.axis == DimensionAxis.HORIZONTAL }.take(MAX_PAIR_CANDIDATES)
        val verticals = accepted.filter { it.axis == DimensionAxis.VERTICAL }.take(MAX_PAIR_CANDIDATES)

        var bestPair: PairResolution? = null
        for (horizontal in horizontals) {
            for (vertical in verticals) {
                val horizontalScale = horizontal.meters / imageWidth.toFloat()
                val verticalScale = vertical.meters / imageHeight.toFloat()
                val meanScale = (horizontalScale + verticalScale) * 0.5f
                if (meanScale <= 0f) continue
                val disagreement = abs(horizontalScale - verticalScale) / meanScale
                if (disagreement > MAX_AXIS_SCALE_DISAGREEMENT) continue

                val consistency = (1f - disagreement / MAX_AXIS_SCALE_DISAGREEMENT).coerceIn(0f, 1f)
                val pairScore = (
                    horizontal.score * 0.37f +
                        vertical.score * 0.37f +
                        consistency * 0.26f
                    ).coerceIn(0f, 0.98f)
                val longSide = meanScale * max(imageWidth, imageHeight).toFloat()
                if (longSide !in MIN_OVERALL_METERS..MAX_DERIVED_LONG_SIDE_METERS) continue

                val candidate = PairResolution(longSide, pairScore)
                if (bestPair == null || candidate.confidence > bestPair.confidence) {
                    bestPair = candidate
                }
            }
        }

        bestPair?.let { pair ->
            if (pair.confidence >= MIN_PAIR_ACCEPTED_SCORE) {
                return ScaleCalibration(
                    longSideMeters = pair.longSideMeters,
                    confidence = pair.confidence,
                    source = "bundled_ocr_axis_pair",
                )
            }
        }

        val best = accepted.first()
        if (best.score < MIN_ACCEPTED_SCORE) return null
        val longSide = deriveLongSideFromAxis(best, imageWidth, imageHeight)
        if (longSide !in MIN_OVERALL_METERS..MAX_DERIVED_LONG_SIDE_METERS) return null
        return ScaleCalibration(
            longSideMeters = longSide,
            confidence = best.score.coerceIn(0f, 0.94f),
            source = when (best.axis) {
                DimensionAxis.HORIZONTAL -> "bundled_ocr_horizontal"
                DimensionAxis.VERTICAL -> "bundled_ocr_vertical"
                DimensionAxis.UNKNOWN -> "bundled_ocr"
            },
        )
    }

    private fun deriveLongSideFromAxis(
        evidence: AxisDimensionEvidence,
        imageWidth: Int,
        imageHeight: Int,
    ): Float = when (evidence.axis) {
        DimensionAxis.HORIZONTAL ->
            evidence.meters / imageWidth.toFloat() * max(imageWidth, imageHeight).toFloat()
        DimensionAxis.VERTICAL ->
            evidence.meters / imageHeight.toFloat() * max(imageWidth, imageHeight).toFloat()
        DimensionAxis.UNKNOWN -> evidence.meters
    }

    private fun inferAxis(box: Rect, imageWidth: Int, imageHeight: Int): DimensionAxis {
        if (imageWidth <= 0 || imageHeight <= 0) return DimensionAxis.UNKNOWN
        val cx = box.exactCenterX() / imageWidth.toFloat()
        val cy = box.exactCenterY() / imageHeight.toFloat()
        val horizontalBorderDistance = min(cy, 1f - cy)
        val verticalBorderDistance = min(cx, 1f - cx)
        val nearest = min(horizontalBorderDistance, verticalBorderDistance)
        if (nearest > MAX_AXIS_BORDER_DISTANCE) return DimensionAxis.UNKNOWN
        return if (horizontalBorderDistance + AXIS_TIE_MARGIN < verticalBorderDistance) {
            DimensionAxis.HORIZONTAL
        } else if (verticalBorderDistance + AXIS_TIE_MARGIN < horizontalBorderDistance) {
            DimensionAxis.VERTICAL
        } else {
            DimensionAxis.UNKNOWN
        }
    }

    private fun candidateScore(
        rawText: String,
        meters: Float,
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
    ): Float {
        if (imageWidth <= 0 || imageHeight <= 0) return 0f
        val cx = box.exactCenterX() / imageWidth.toFloat()
        val cy = box.exactCenterY() / imageHeight.toFloat()
        val borderDistance = min(min(cx, 1f - cx), min(cy, 1f - cy)).coerceIn(0f, 0.5f)
        val perimeterScore = (1f - borderDistance / 0.34f).coerceIn(0f, 1f)
        val decimalScore = if (rawText.contains('.') || rawText.contains(',') || rawText.contains('٫')) 0.11f else 0f
        val overallPlausibility = when (meters) {
            in 8f..30f -> 0.38f
            in 6f..<8f -> 0.25f
            else -> 0.18f
        }
        return (perimeterScore * 0.52f + overallPlausibility + decimalScore).coerceIn(0f, 1f)
    }

    /**
     * Normalizes Western, Arabic-Indic and Eastern Arabic-Indic numerals.
     * CAD drawings also often express dimensions in millimetres (e.g. 14000), which are converted
     * to metres. Values already in metre notation (e.g. 14.00) are kept as-is.
     */
    internal fun parseDimensionMeters(source: String): Float? {
        if (source.isBlank()) return null
        val normalizedDigits = buildString(source.length) {
            source.forEach { char ->
                append(
                    when (char) {
                        '٠', '۰' -> '0'
                        '١', '۱' -> '1'
                        '٢', '۲' -> '2'
                        '٣', '۳' -> '3'
                        '٤', '۴' -> '4'
                        '٥', '۵' -> '5'
                        '٦', '۶' -> '6'
                        '٧', '۷' -> '7'
                        '٨', '۸' -> '8'
                        '٩', '۹' -> '9'
                        '٫', ',' -> '.'
                        else -> char
                    }
                )
            }
        }
        val token = NUMBER.find(normalizedDigits)?.value ?: return null
        val raw = token.toFloatOrNull() ?: return null
        if (raw <= 0f) return null

        return when {
            raw in 2f..60f -> raw
            raw in 600f..60_000f -> raw / 1000f
            else -> null
        }
    }

    private fun fallback() = ScaleCalibration(
        longSideMeters = DEFAULT_LONG_SIDE_METERS,
        confidence = 0.28f,
        source = "geometry_fallback",
    )

    private data class PairResolution(
        val longSideMeters: Float,
        val confidence: Float,
    )

    private val NUMBER = Regex("[0-9]+(?:\\.[0-9]{1,3})?")
    private const val DEFAULT_LONG_SIDE_METERS = 14f
    private const val MIN_OVERALL_METERS = 6f
    private const val MAX_OVERALL_METERS = 45f
    private const val MAX_DERIVED_LONG_SIDE_METERS = 60f
    private const val MIN_EVIDENCE_SCORE = 0.52f
    private const val MIN_ACCEPTED_SCORE = 0.64f
    private const val MIN_PAIR_ACCEPTED_SCORE = 0.70f
    private const val MAX_AXIS_SCALE_DISAGREEMENT = 0.16f
    private const val MAX_PAIR_CANDIDATES = 6
    private const val MAX_AXIS_BORDER_DISTANCE = 0.23f
    private const val AXIS_TIE_MARGIN = 0.025f
}
