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
import kotlin.math.min

internal data class ScaleCalibration(
    val longSideMeters: Float,
    val confidence: Float,
    val source: String,
)

/**
 * Reads printed architectural dimensions using a bundled on-device ML Kit OCR model.
 *
 * We only accept a candidate as an overall-scale hint when it is physically plausible and placed
 * close to the drawing perimeter. If evidence is weak, the geometry pipeline falls back to its
 * conservative baseline instead of silently distorting the house.
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
        val candidates = ArrayList<DimensionCandidate>()
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
                    candidates += DimensionCandidate(meters, score)
                }
            }
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.score < MIN_ACCEPTED_SCORE) return null
        return ScaleCalibration(
            longSideMeters = best.meters,
            confidence = best.score.coerceIn(0f, 0.94f),
            source = "bundled_ocr",
        )
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
        return perimeterScore * 0.52f + overallPlausibility + decimalScore
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
            raw in 600f..60_000f -> raw / 1000f // common CAD millimetre notation
            else -> null
        }
    }

    private fun fallback() = ScaleCalibration(
        longSideMeters = DEFAULT_LONG_SIDE_METERS,
        confidence = 0.28f,
        source = "geometry_fallback",
    )

    private data class DimensionCandidate(
        val meters: Float,
        val score: Float,
    )

    private val NUMBER = Regex("[0-9]+(?:\\.[0-9]{1,3})?")
    private const val DEFAULT_LONG_SIDE_METERS = 14f
    private const val MIN_OVERALL_METERS = 6f
    private const val MAX_OVERALL_METERS = 45f
    private const val MIN_ACCEPTED_SCORE = 0.64f
}
