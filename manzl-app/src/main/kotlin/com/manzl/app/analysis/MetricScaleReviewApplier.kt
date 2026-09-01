package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlin.math.max

/**
 * Applies an explicit user-supplied overall dimension to low-confidence metric plans.
 *
 * The raster/image topology is never reinterpreted here. We only apply one uniform X/Z scale to a
 * derived metric copy, preserving wall directions and room topology. Vertical architectural values
 * (wall heights, sill heights and floor-to-floor heights) are intentionally not scaled because they
 * are independent from the drawing's 2D pixel scale.
 */
internal object MetricScaleReviewApplier {

    fun needsReview(plan: FloorPlan): Boolean =
        plan.scaleConfidence < TRUSTED_SCALE_CONFIDENCE || plan.scaleSource == FALLBACK_SOURCE

    fun needsReview(building: BuildingPlan): Boolean = building.levels.any { needsReview(it.plan) }

    fun currentLongSideMeters(plan: FloorPlan): Float = max(plan.widthMeters, plan.depthMeters)

    fun isPlausibleLongSideMeters(value: Float): Boolean = value in MIN_LONG_SIDE_METERS..MAX_LONG_SIDE_METERS

    fun apply(
        source: BuildingPlan,
        reviewedLongSideMetersByLevelId: Map<String, Float>,
    ): MetricScaleReviewResult {
        if (source.levels.isEmpty() || reviewedLongSideMetersByLevelId.isEmpty()) {
            return MetricScaleReviewResult(source, correctedLevelCount = 0)
        }

        var correctedCount = 0
        val correctedLevels = source.levels.map { level ->
            val requested = reviewedLongSideMetersByLevelId[level.id]
            if (requested == null || !isPlausibleLongSideMeters(requested)) {
                level
            } else {
                val current = currentLongSideMeters(level.plan)
                if (current <= EPSILON) {
                    level
                } else {
                    val ratio = requested / current
                    if (ratio !in MIN_SCALE_RATIO..MAX_SCALE_RATIO) {
                        level
                    } else {
                        correctedCount++
                        level.copy(plan = level.plan.rescaledMetric(ratio))
                    }
                }
            }
        }

        if (correctedCount == 0) return MetricScaleReviewResult(source, correctedLevelCount = 0)

        // Re-link stairs and re-run registration diagnostics after scale correction because the
        // relative X/Z positions and stair widths/runs may have changed.
        val geometryOnly = BuildingPlan(levels = correctedLevels)
        val linked = if (correctedLevels.size > 1) StairLevelLinker.link(geometryOnly) else geometryOnly
        val corrected = linked.copy(
            registrationDiagnostics = FloorRegistrationDiagnostics.diagnose(linked),
        )
        return MetricScaleReviewResult(corrected, correctedLevelCount = correctedCount)
    }

    private fun FloorPlan.rescaledMetric(ratio: Float): FloorPlan = copy(
        widthMeters = widthMeters * ratio,
        depthMeters = depthMeters * ratio,
        walls = walls.map { wall ->
            wall.copy(
                start = wall.start.scaled(ratio),
                end = wall.end.scaled(ratio),
            )
        },
        doors = doors.map { door ->
            door.copy(
                center = door.center.scaled(ratio),
                widthMeters = door.widthMeters * ratio,
            )
        },
        windows = windows.map { window ->
            window.copy(
                center = window.center.scaled(ratio),
                widthMeters = window.widthMeters * ratio,
            )
        },
        stairs = stairs.map { stair ->
            stair.copy(
                center = stair.center.scaled(ratio),
                widthMeters = stair.widthMeters * ratio,
                runMeters = stair.runMeters * ratio,
            )
        },
        rooms = rooms.map { room ->
            room.copy(polygon = room.polygon.map { point -> point.scaled(ratio) })
        },
        scaleConfidence = USER_SCALE_CONFIDENCE,
        scaleSource = USER_SCALE_SOURCE,
    )

    private fun Vec2.scaled(ratio: Float): Vec2 = Vec2(x * ratio, z * ratio)

    private const val TRUSTED_SCALE_CONFIDENCE = 0.64f
    private const val FALLBACK_SOURCE = "geometry_fallback"
    private const val USER_SCALE_SOURCE = "user_overall_dimension"
    private const val USER_SCALE_CONFIDENCE = 1f
    private const val MIN_LONG_SIDE_METERS = 4f
    private const val MAX_LONG_SIDE_METERS = 80f
    private const val MIN_SCALE_RATIO = 0.20f
    private const val MAX_SCALE_RATIO = 5.0f
    private const val EPSILON = 0.0001f
}

internal data class MetricScaleReviewResult(
    val building: BuildingPlan,
    val correctedLevelCount: Int,
)
