package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Detects the conventional quarter-circle door swing symbol around an already validated opening.
 *
 * This is deliberately an enrichment pass, not a topology detector: it cannot create, move or
 * resize a door. All plan↔raster sampling uses the structural content envelope saved by the wall
 * analyzer, preventing screenshot margins from shifting arc hypotheses away from the real door.
 */
internal object DoorSwingArcDetector {

    fun enrich(bitmap: Bitmap, plan: FloorPlan): List<DoorOpening> {
        if (plan.doors.isEmpty() || bitmap.width <= 0 || bitmap.height <= 0) return plan.doors
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return enrichWithSampler(
            plan = plan,
            widthPx = bitmap.width,
            heightPx = bitmap.height,
        ) { x, y ->
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) 0f
            else inkStrength(pixels[y * bitmap.width + x])
        }
    }

    internal fun enrichWithSampler(
        plan: FloorPlan,
        widthPx: Int,
        heightPx: Int,
        inkAt: (Int, Int) -> Float,
    ): List<DoorOpening> {
        if (
            plan.doors.isEmpty() ||
            widthPx <= 0 ||
            heightPx <= 0 ||
            plan.widthMeters <= 0f ||
            plan.depthMeters <= 0f
        ) {
            return plan.doors
        }

        val transform = PlanRasterTransform.forImage(plan, widthPx, heightPx)
        val pixelsPerMeter = min(transform.pixelsPerMeterX, transform.pixelsPerMeterZ)
        if (!pixelsPerMeter.isFinite() || pixelsPerMeter < MIN_PIXELS_PER_METER) return plan.doors

        return plan.doors.map { door ->
            if (
                door.hingeSide != DoorHingeSide.UNKNOWN &&
                door.swingSide != DoorSwingSide.UNKNOWN &&
                door.swingConfidence >= KEEP_EXISTING_CONFIDENCE
            ) {
                door
            } else {
                detectDoor(
                    door = door,
                    transform = transform,
                    pixelsPerMeter = pixelsPerMeter,
                    inkAt = inkAt,
                ) ?: door
            }
        }
    }

    private fun detectDoor(
        door: DoorOpening,
        transform: PlanRasterTransform,
        pixelsPerMeter: Float,
        inkAt: (Int, Int) -> Float,
    ): DoorOpening? {
        if (door.widthMeters !in MIN_DOOR_WIDTH_METERS..MAX_DOOR_WIDTH_METERS) return null

        val radians = door.rotationDegrees * (PI.toFloat() / 180f)
        val axis = Vec2(cos(radians), sin(radians))
        val normal = Vec2(-axis.z, axis.x)
        val halfWidth = door.widthMeters * 0.5f
        val leafRadius = door.widthMeters * LEAF_RADIUS_RATIO
        val samplingRadiusPx = (pixelsPerMeter * LOCAL_SAMPLE_RADIUS_METERS)
            .roundToInt()
            .coerceIn(1, MAX_LOCAL_SAMPLE_RADIUS_PX)

        val hypotheses = buildList {
            for (hingeSide in listOf(DoorHingeSide.AXIS_START, DoorHingeSide.AXIS_END)) {
                val hingeSign = if (hingeSide == DoorHingeSide.AXIS_START) -1f else 1f
                val hinge = Vec2(
                    x = door.center.x + axis.x * halfWidth * hingeSign,
                    z = door.center.z + axis.z * halfWidth * hingeSign,
                )
                val closedDirectionSign = -hingeSign
                val closedDirection = Vec2(
                    x = axis.x * closedDirectionSign,
                    z = axis.z * closedDirectionSign,
                )

                for (swingSide in listOf(DoorSwingSide.NEGATIVE_NORMAL, DoorSwingSide.POSITIVE_NORMAL)) {
                    val normalSign = if (swingSide == DoorSwingSide.POSITIVE_NORMAL) 1f else -1f
                    val targetNormal = Vec2(normal.x * normalSign, normal.z * normalSign)
                    val score = scoreQuarterArc(
                        hinge = hinge,
                        closedDirection = closedDirection,
                        targetNormal = targetNormal,
                        radiusMeters = leafRadius,
                        transform = transform,
                        localRadiusPx = samplingRadiusPx,
                        inkAt = inkAt,
                    )
                    add(Hypothesis(hingeSide, swingSide, score))
                }
            }
        }.sortedByDescending { it.score }

        val best = hypotheses.firstOrNull() ?: return null
        val runnerUp = hypotheses.getOrNull(1)?.score ?: 0f
        if (best.score < MIN_ACCEPTED_SCORE) return null
        if (best.score - runnerUp < MIN_WINNING_MARGIN) return null

        val confidence = (
            BASE_CONFIDENCE +
                (best.score - MIN_ACCEPTED_SCORE) * SCORE_CONFIDENCE_GAIN +
                (best.score - runnerUp) * MARGIN_CONFIDENCE_GAIN
            ).coerceIn(MIN_REPORTED_CONFIDENCE, MAX_REPORTED_CONFIDENCE)

        return door.copy(
            hingeSide = best.hingeSide,
            swingSide = best.swingSide,
            swingConfidence = confidence,
            evidenceKind = if (door.evidenceKind == DoorEvidenceKind.USER_CONFIRMED) {
                DoorEvidenceKind.USER_CONFIRMED
            } else {
                DoorEvidenceKind.SEMANTIC_CONFIRMED
            },
        )
    }

    private fun scoreQuarterArc(
        hinge: Vec2,
        closedDirection: Vec2,
        targetNormal: Vec2,
        radiusMeters: Float,
        transform: PlanRasterTransform,
        localRadiusPx: Int,
        inkAt: (Int, Int) -> Float,
    ): Float {
        var total = 0f
        var covered = 0
        var early = 0f
        var middle = 0f
        var late = 0f

        ANGLES_DEGREES.forEachIndexed { index, degrees ->
            val angle = degrees * (PI.toFloat() / 180f)
            val direction = Vec2(
                x = closedDirection.x * cos(angle) + targetNormal.x * sin(angle),
                z = closedDirection.z * cos(angle) + targetNormal.z * sin(angle),
            )
            val point = Vec2(
                x = hinge.x + direction.x * radiusMeters,
                z = hinge.z + direction.z * radiusMeters,
            )
            val pixel = transform.planToImage(point)
            val strength = maxInkAround(
                pixel.first.roundToInt(),
                pixel.second.roundToInt(),
                localRadiusPx,
                inkAt,
            )
            total += strength
            if (strength >= COVERED_POINT_STRENGTH) covered++
            when {
                index < ANGLES_DEGREES.size / 3 -> early += strength
                index < ANGLES_DEGREES.size * 2 / 3 -> middle += strength
                else -> late += strength
            }
        }

        val sampleCount = ANGLES_DEGREES.size.toFloat()
        val average = total / sampleCount
        val coverage = covered / sampleCount
        val third = max(1, ANGLES_DEGREES.size / 3).toFloat()
        val continuity = min(early / third, min(middle / third, late / third))
        return (
            average * AVERAGE_WEIGHT +
                coverage * COVERAGE_WEIGHT +
                continuity * CONTINUITY_WEIGHT
            ).coerceIn(0f, 1f)
    }

    private fun maxInkAround(
        centerX: Int,
        centerY: Int,
        radius: Int,
        inkAt: (Int, Int) -> Float,
    ): Float {
        var strongest = 0f
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                strongest = max(strongest, inkAt(centerX + dx, centerY + dy).coerceIn(0f, 1f))
            }
        }
        return strongest
    }

    private fun inkStrength(color: Int): Float {
        val r = Color.red(color).toFloat()
        val g = Color.green(color).toFloat()
        val b = Color.blue(color).toFloat()
        val luminance = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val darkest = ((196f - luminance) / 150f).coerceIn(0f, 1f)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val saturation = maxChannel - minChannel
        val blueprint = if (b > r * 1.12f && b > g * 1.02f && saturation > 25f) {
            ((saturation - 25f) / 100f).coerceIn(0f, 1f)
        } else {
            0f
        }
        return max(darkest, blueprint)
    }

    private data class Hypothesis(
        val hingeSide: DoorHingeSide,
        val swingSide: DoorSwingSide,
        val score: Float,
    )

    private val ANGLES_DEGREES = intArrayOf(24, 32, 40, 48, 56, 64, 72, 80, 86)
    private const val MIN_DOOR_WIDTH_METERS = 0.60f
    private const val MAX_DOOR_WIDTH_METERS = 1.65f
    private const val LEAF_RADIUS_RATIO = 0.93f
    private const val LOCAL_SAMPLE_RADIUS_METERS = 0.025f
    private const val MAX_LOCAL_SAMPLE_RADIUS_PX = 4
    private const val MIN_PIXELS_PER_METER = 9f
    private const val COVERED_POINT_STRENGTH = 0.34f
    private const val AVERAGE_WEIGHT = 0.52f
    private const val COVERAGE_WEIGHT = 0.30f
    private const val CONTINUITY_WEIGHT = 0.18f
    private const val MIN_ACCEPTED_SCORE = 0.36f
    private const val MIN_WINNING_MARGIN = 0.065f
    private const val KEEP_EXISTING_CONFIDENCE = 0.74f
    private const val BASE_CONFIDENCE = 0.66f
    private const val SCORE_CONFIDENCE_GAIN = 0.52f
    private const val MARGIN_CONFIDENCE_GAIN = 0.55f
    private const val MIN_REPORTED_CONFIDENCE = 0.66f
    private const val MAX_REPORTED_CONFIDENCE = 0.94f
}
