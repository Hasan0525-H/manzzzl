package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorRegistrationDiagnostic
import com.manzl.app.model.FloorRegistrationEvidence
import com.manzl.app.model.FloorRegistrationStatus
import com.manzl.app.model.Staircase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Diagnoses likely X/Z registration mismatch between independently cropped floor drawings.
 *
 * The result is metadata only. No wall, room, opening or stair is translated here. When a strong
 * staircase-shaft correspondence implies that the upper drawing uses a different crop/origin, the
 * suggested translation is surfaced for review instead of being silently applied.
 */
internal object FloorRegistrationDiagnostics {

    fun diagnose(building: BuildingPlan): List<FloorRegistrationDiagnostic> {
        if (building.levels.size < 2) return emptyList()
        val levels = building.levels.sortedBy { it.levelIndex }
        return buildList {
            for (index in 0 until levels.lastIndex) {
                add(diagnoseAdjacent(levels[index], levels[index + 1]))
            }
        }
    }

    private fun diagnoseAdjacent(lower: FloorLevel, upper: FloorLevel): FloorRegistrationDiagnostic {
        bestStairPair(lower, upper)?.let { match ->
            val offsetX = match.lower.center.x - match.upper.center.x
            val offsetZ = match.lower.center.z - match.upper.center.z
            val offsetMagnitude = sqrt(offsetX * offsetX + offsetZ * offsetZ)
            val status = when {
                offsetMagnitude <= ALIGNED_TRANSLATION_METERS -> FloorRegistrationStatus.ALIGNED
                offsetMagnitude <= MAX_REVIEWABLE_TRANSLATION_METERS -> FloorRegistrationStatus.REVIEW_REQUIRED
                else -> FloorRegistrationStatus.UNRESOLVED
            }
            val translationPenalty = (offsetMagnitude / MAX_REVIEWABLE_TRANSLATION_METERS)
                .coerceIn(0f, 1f)
            val confidence = if (status == FloorRegistrationStatus.UNRESOLVED) {
                (match.score * 0.58f).coerceIn(0f, 0.70f)
            } else {
                (match.score * (1f - translationPenalty * 0.14f)).coerceIn(0f, 0.96f)
            }
            return FloorRegistrationDiagnostic(
                lowerLevelId = lower.id,
                upperLevelId = upper.id,
                status = status,
                evidence = FloorRegistrationEvidence.STAIR_SHAFT,
                suggestedOffsetXMeters = if (status == FloorRegistrationStatus.UNRESOLVED) 0f else offsetX,
                suggestedOffsetZMeters = if (status == FloorRegistrationStatus.UNRESOLVED) 0f else offsetZ,
                confidence = confidence,
            )
        }

        val widthRatio = ratio(lower.plan.widthMeters, upper.plan.widthMeters)
        val depthRatio = ratio(lower.plan.depthMeters, upper.plan.depthMeters)
        val sourceAspectLower = lower.plan.sourceWidthPx.toFloat() / lower.plan.sourceHeightPx.coerceAtLeast(1)
        val sourceAspectUpper = upper.plan.sourceWidthPx.toFloat() / upper.plan.sourceHeightPx.coerceAtLeast(1)
        val aspectRatio = ratio(sourceAspectLower, sourceAspectUpper)
        val footprintConfidence = (
            widthRatio * 0.42f +
                depthRatio * 0.42f +
                aspectRatio * 0.16f
            ).coerceIn(0f, 1f)

        // Similar bounds are useful evidence that no major crop shift exists, but without a shared
        // physical anchor such as a stair shaft they are never strong enough to propose translation.
        val status = if (
            widthRatio >= MIN_FOOTPRINT_ALIGNED_RATIO &&
            depthRatio >= MIN_FOOTPRINT_ALIGNED_RATIO &&
            aspectRatio >= MIN_ASPECT_ALIGNED_RATIO
        ) {
            FloorRegistrationStatus.ALIGNED
        } else {
            FloorRegistrationStatus.UNRESOLVED
        }

        return FloorRegistrationDiagnostic(
            lowerLevelId = lower.id,
            upperLevelId = upper.id,
            status = status,
            evidence = if (footprintConfidence >= MIN_FOOTPRINT_EVIDENCE_CONFIDENCE) {
                FloorRegistrationEvidence.FOOTPRINT_ONLY
            } else {
                FloorRegistrationEvidence.NONE
            },
            confidence = if (status == FloorRegistrationStatus.ALIGNED) {
                (footprintConfidence * 0.72f).coerceAtMost(MAX_FOOTPRINT_ONLY_CONFIDENCE)
            } else {
                (footprintConfidence * 0.42f).coerceAtMost(0.48f)
            },
        )
    }

    private fun bestStairPair(lower: FloorLevel, upper: FloorLevel): StairMatch? {
        var best: StairMatch? = null
        lower.plan.stairs.forEach { lowerStair ->
            if (lowerStair.confidence < MIN_STAIR_CONFIDENCE) return@forEach
            upper.plan.stairs.forEach { upperStair ->
                if (upperStair.confidence < MIN_STAIR_CONFIDENCE) return@forEach
                val score = scoreStairShape(lowerStair, upperStair) ?: return@forEach
                if (best == null || score > best!!.score) {
                    best = StairMatch(lowerStair, upperStair, score)
                }
            }
        }
        return best?.takeIf { it.score >= MIN_STAIR_MATCH_SCORE }
    }

    private fun scoreStairShape(lower: Staircase, upper: Staircase): Float? {
        val axisDelta = axisAngleDelta(lower.rotationDegrees, upper.rotationDegrees)
        if (axisDelta > MAX_AXIS_DELTA_DEGREES) return null

        val widthRatio = ratio(lower.widthMeters, upper.widthMeters)
        val runRatio = ratio(lower.runMeters, upper.runMeters)
        if (widthRatio < MIN_STAIR_WIDTH_RATIO || runRatio < MIN_STAIR_RUN_RATIO) return null

        val stepRatio = ratio(lower.stepCount.toFloat(), upper.stepCount.toFloat())
        val heightDelta = abs(lower.floorToFloorHeightMeters - upper.floorToFloorHeightMeters)
        if (heightDelta > MAX_STAIR_HEIGHT_DELTA_METERS) return null

        val axisScore = (1f - axisDelta / MAX_AXIS_DELTA_DEGREES).coerceIn(0f, 1f)
        val heightScore = (1f - heightDelta / MAX_STAIR_HEIGHT_DELTA_METERS).coerceIn(0f, 1f)
        val evidence = min(lower.confidence, upper.confidence).coerceIn(0f, 1f)
        return (
            widthRatio * 0.23f +
                runRatio * 0.20f +
                stepRatio * 0.15f +
                axisScore * 0.17f +
                heightScore * 0.10f +
                evidence * 0.15f
            ).coerceIn(0f, 1f)
    }

    private fun axisAngleDelta(a: Float, b: Float): Float {
        val normalizedA = normalize180(a)
        val normalizedB = normalize180(b)
        val raw = abs(normalizedA - normalizedB)
        return min(raw, 180f - raw)
    }

    private fun normalize180(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private fun ratio(a: Float, b: Float): Float {
        val high = max(a, b)
        val low = min(a, b)
        return if (high <= 0.0001f) 0f else low / high
    }

    private data class StairMatch(
        val lower: Staircase,
        val upper: Staircase,
        val score: Float,
    )

    private const val MIN_STAIR_CONFIDENCE = 0.66f
    private const val MAX_AXIS_DELTA_DEGREES = 24f
    private const val MIN_STAIR_WIDTH_RATIO = 0.66f
    private const val MIN_STAIR_RUN_RATIO = 0.56f
    private const val MAX_STAIR_HEIGHT_DELTA_METERS = 0.65f
    private const val MIN_STAIR_MATCH_SCORE = 0.64f
    private const val ALIGNED_TRANSLATION_METERS = 0.32f
    private const val MAX_REVIEWABLE_TRANSLATION_METERS = 8.0f
    private const val MIN_FOOTPRINT_ALIGNED_RATIO = 0.90f
    private const val MIN_ASPECT_ALIGNED_RATIO = 0.94f
    private const val MIN_FOOTPRINT_EVIDENCE_CONFIDENCE = 0.72f
    private const val MAX_FOOTPRINT_ONLY_CONFIDENCE = 0.70f
}
