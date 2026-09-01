package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.FloorRegistrationEvidence
import com.manzl.app.model.FloorRegistrationStatus
import com.manzl.app.model.Vec2

/**
 * Applies only explicit, reviewable cross-floor registration suggestions to a derived runtime copy.
 *
 * The caller must invoke this after an explicit user action. The canonical [BuildingPlan] is never
 * mutated: every wall/opening/stair/room translation is performed on copied model objects. This lets
 * the UI offer an immediate revert while preserving the uploaded drawings as the source of truth.
 */
internal object ReviewedRegistrationApplier {

    fun hasApplicableCorrection(building: BuildingPlan): Boolean =
        building.registrationDiagnostics.any(::isApplicable)

    fun applyAllReviewable(source: BuildingPlan): RegistrationCorrectionResult {
        if (source.levels.size < 2 || !hasApplicableCorrection(source)) {
            return RegistrationCorrectionResult(source, appliedPairCount = 0)
        }

        val sorted = source.levels.sortedBy { it.levelIndex }
        val diagnostics = source.registrationDiagnostics.associateBy {
            it.lowerLevelId to it.upperLevelId
        }
        val offsets = HashMap<String, Vec2>()
        offsets[sorted.first().id] = Vec2(0f, 0f)
        var applied = 0

        for (index in 0 until sorted.lastIndex) {
            val lower = sorted[index]
            val upper = sorted[index + 1]
            val lowerOffset = offsets[lower.id] ?: Vec2(0f, 0f)
            val diagnostic = diagnostics[lower.id to upper.id]

            offsets[upper.id] = when {
                diagnostic == null -> Vec2(0f, 0f)
                diagnostic.status == FloorRegistrationStatus.ALIGNED -> lowerOffset
                isApplicable(diagnostic) -> {
                    applied++
                    Vec2(
                        x = lowerOffset.x + diagnostic.suggestedOffsetXMeters,
                        z = lowerOffset.z + diagnostic.suggestedOffsetZMeters,
                    )
                }
                else -> Vec2(0f, 0f)
            }
        }

        if (applied == 0) return RegistrationCorrectionResult(source, appliedPairCount = 0)

        val correctedLevels = source.levels.map { level ->
            val offset = offsets[level.id] ?: Vec2(0f, 0f)
            if (offset.isZero()) level else level.copy(plan = level.plan.translated(offset))
        }

        val geometryOnly = BuildingPlan(levels = correctedLevels)
        val linked = if (correctedLevels.size > 1) StairLevelLinker.link(geometryOnly) else geometryOnly
        val corrected = linked.copy(
            registrationDiagnostics = FloorRegistrationDiagnostics.diagnose(linked),
        )
        return RegistrationCorrectionResult(corrected, appliedPairCount = applied)
    }

    private fun isApplicable(diagnostic: com.manzl.app.model.FloorRegistrationDiagnostic): Boolean {
        if (diagnostic.status != FloorRegistrationStatus.REVIEW_REQUIRED) return false
        if (diagnostic.evidence != FloorRegistrationEvidence.STAIR_SHAFT) return false
        if (diagnostic.confidence < MIN_APPROVAL_CONFIDENCE) return false
        val magnitudeSquared =
            diagnostic.suggestedOffsetXMeters * diagnostic.suggestedOffsetXMeters +
                diagnostic.suggestedOffsetZMeters * diagnostic.suggestedOffsetZMeters
        return magnitudeSquared <= MAX_APPROVED_TRANSLATION_METERS * MAX_APPROVED_TRANSLATION_METERS
    }

    private fun FloorPlan.translated(offset: Vec2): FloorPlan = copy(
        walls = walls.map { wall ->
            wall.copy(
                start = wall.start + offset,
                end = wall.end + offset,
            )
        },
        doors = doors.map { door -> door.copy(center = door.center + offset) },
        windows = windows.map { window -> window.copy(center = window.center + offset) },
        stairs = stairs.map { stair -> stair.copy(center = stair.center + offset) },
        rooms = rooms.map { room ->
            room.copy(polygon = room.polygon.map { point -> point + offset })
        },
    )

    private fun Vec2.isZero(): Boolean =
        kotlin.math.abs(x) <= OFFSET_EPSILON_METERS && kotlin.math.abs(z) <= OFFSET_EPSILON_METERS

    private operator fun Vec2.plus(other: Vec2): Vec2 = Vec2(x + other.x, z + other.z)

    private const val MIN_APPROVAL_CONFIDENCE = 0.72f
    private const val MAX_APPROVED_TRANSLATION_METERS = 8.0f
    private const val OFFSET_EPSILON_METERS = 0.0001f
}

internal data class RegistrationCorrectionResult(
    val building: BuildingPlan,
    val appliedPairCount: Int,
)
