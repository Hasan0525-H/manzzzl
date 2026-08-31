package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

internal enum class WallEndpoint {
    START,
    END,
}

/** Explicit edits are the only non-CV path allowed to change measured wall topology. */
internal sealed interface GeometryCorrection {
    data class MoveEndpoint(
        val wallIndex: Int,
        val endpoint: WallEndpoint,
        val target: Vec2,
    ) : GeometryCorrection

    data class TranslateWall(
        val wallIndex: Int,
        val deltaX: Float,
        val deltaZ: Float,
    ) : GeometryCorrection

    data class SetThickness(
        val wallIndex: Int,
        val thicknessMeters: Float,
    ) : GeometryCorrection

    data class AddWall(
        val start: Vec2,
        val end: Vec2,
        val thicknessMeters: Float,
    ) : GeometryCorrection

    data class DeleteWall(val wallIndex: Int) : GeometryCorrection
}

internal data class GeometryCorrectionApplyResult(
    val plan: FloorPlan,
    val appliedCount: Int,
    val rejectedCount: Int,
)

internal data class VerifiedGeometryCorrectionResult(
    val plan: FloorPlan,
    val fidelity: GeometryFidelityReport,
    val appliedCount: Int,
    val rejectedCount: Int,
)

/**
 * Applies only explicit user edits and then re-runs the same independent raster fidelity evaluator.
 *
 * No correction is allowed to directly set PASS. The source raster is classified again and PASS can
 * only come from [GeometryFidelityEvaluator]. Derived rooms/openings are cleared because they must be
 * rebuilt from the corrected geometry by the normal semantic pipeline.
 */
internal object GeometryCorrectionEngine {

    fun apply(plan: FloorPlan, corrections: List<GeometryCorrection>): GeometryCorrectionApplyResult {
        if (corrections.isEmpty()) {
            return GeometryCorrectionApplyResult(plan, appliedCount = 0, rejectedCount = 0)
        }

        val walls = plan.walls.toMutableList()
        var applied = 0
        var rejected = 0
        corrections.forEach { correction ->
            val accepted = when (correction) {
                is GeometryCorrection.MoveEndpoint -> moveEndpoint(plan, walls, correction)
                is GeometryCorrection.TranslateWall -> translateWall(plan, walls, correction)
                is GeometryCorrection.SetThickness -> setThickness(walls, correction)
                is GeometryCorrection.AddWall -> addWall(plan, walls, correction)
                is GeometryCorrection.DeleteWall -> deleteWall(walls, correction)
            }
            if (accepted) applied++ else rejected++
        }

        val corrected = plan.copy(
            walls = walls.toList(),
            doors = emptyList(),
            windows = emptyList(),
            stairs = emptyList(),
            rooms = emptyList(),
            geometryFidelity = GeometryFidelityReport.UNKNOWN,
        )
        return GeometryCorrectionApplyResult(corrected, applied, rejected)
    }

    suspend fun applyAndVerify(
        source: Bitmap,
        plan: FloorPlan,
        corrections: List<GeometryCorrection>,
        maxAnalysisSide: Int = DEFAULT_CORRECTION_ANALYSIS_SIDE,
    ): VerifiedGeometryCorrectionResult = withContext(Dispatchers.Default) {
        val applied = apply(plan, corrections)
        val working = source.downscale(maxAnalysisSide)
        val structural = StructuralRasterMask.classify(working)
        val fidelity = GeometryFidelityEvaluator.evaluate(
            structuralMask = structural.mask,
            imageWidth = working.width,
            imageHeight = working.height,
            plan = applied.plan,
        )
        if (working !== source && !working.isRecycled) working.recycle()

        val confidence = (
            applied.plan.analysisConfidence.coerceIn(0f, 1f) * 0.45f +
                fidelity.score.coerceIn(0f, 1f) * 0.55f
            ).coerceIn(0f, 0.99f)
        val verified = applied.plan.copy(
            analysisConfidence = confidence,
            geometryFidelity = fidelity,
        )
        VerifiedGeometryCorrectionResult(
            plan = verified,
            fidelity = fidelity,
            appliedCount = applied.appliedCount,
            rejectedCount = applied.rejectedCount,
        )
    }

    private fun moveEndpoint(
        plan: FloorPlan,
        walls: MutableList<WallSegment>,
        correction: GeometryCorrection.MoveEndpoint,
    ): Boolean {
        if (correction.wallIndex !in walls.indices || !insideEditEnvelope(plan, correction.target)) return false
        val wall = walls[correction.wallIndex]
        val updated = when (correction.endpoint) {
            WallEndpoint.START -> wall.copy(start = correction.target, confidence = 1f)
            WallEndpoint.END -> wall.copy(end = correction.target, confidence = 1f)
        }
        if (wallLength(updated) < MIN_WALL_LENGTH_METERS) return false
        walls[correction.wallIndex] = updated
        return true
    }

    private fun translateWall(
        plan: FloorPlan,
        walls: MutableList<WallSegment>,
        correction: GeometryCorrection.TranslateWall,
    ): Boolean {
        if (correction.wallIndex !in walls.indices) return false
        val shift = sqrt(correction.deltaX * correction.deltaX + correction.deltaZ * correction.deltaZ)
        if (!shift.isFinite() || shift > MAX_SINGLE_TRANSLATION_METERS) return false
        val wall = walls[correction.wallIndex]
        val start = Vec2(wall.start.x + correction.deltaX, wall.start.z + correction.deltaZ)
        val end = Vec2(wall.end.x + correction.deltaX, wall.end.z + correction.deltaZ)
        if (!insideEditEnvelope(plan, start) || !insideEditEnvelope(plan, end)) return false
        walls[correction.wallIndex] = wall.copy(start = start, end = end, confidence = 1f)
        return true
    }

    private fun setThickness(
        walls: MutableList<WallSegment>,
        correction: GeometryCorrection.SetThickness,
    ): Boolean {
        if (correction.wallIndex !in walls.indices) return false
        if (!correction.thicknessMeters.isFinite() || correction.thicknessMeters !in MIN_WALL_THICKNESS_METERS..MAX_WALL_THICKNESS_METERS) {
            return false
        }
        walls[correction.wallIndex] = walls[correction.wallIndex].copy(
            thicknessMeters = correction.thicknessMeters,
            confidence = 1f,
        )
        return true
    }

    private fun addWall(
        plan: FloorPlan,
        walls: MutableList<WallSegment>,
        correction: GeometryCorrection.AddWall,
    ): Boolean {
        if (!insideEditEnvelope(plan, correction.start) || !insideEditEnvelope(plan, correction.end)) return false
        if (!correction.thicknessMeters.isFinite() || correction.thicknessMeters !in MIN_WALL_THICKNESS_METERS..MAX_WALL_THICKNESS_METERS) {
            return false
        }
        val candidate = WallSegment(
            start = correction.start,
            end = correction.end,
            thicknessMeters = correction.thicknessMeters,
            heightMeters = medianWallHeight(walls),
            confidence = 1f,
        )
        if (wallLength(candidate) < MIN_WALL_LENGTH_METERS) return false
        walls += candidate
        return true
    }

    private fun deleteWall(
        walls: MutableList<WallSegment>,
        correction: GeometryCorrection.DeleteWall,
    ): Boolean {
        if (correction.wallIndex !in walls.indices || walls.size <= MIN_REMAINING_WALLS) return false
        walls.removeAt(correction.wallIndex)
        return true
    }

    private fun insideEditEnvelope(plan: FloorPlan, point: Vec2): Boolean {
        if (!point.x.isFinite() || !point.z.isFinite()) return false
        val halfWidth = plan.widthMeters * 0.5f + EDIT_ENVELOPE_MARGIN_METERS
        val halfDepth = plan.depthMeters * 0.5f + EDIT_ENVELOPE_MARGIN_METERS
        return point.x in -halfWidth..halfWidth && point.z in -halfDepth..halfDepth
    }

    private fun medianWallHeight(walls: List<WallSegment>): Float {
        if (walls.isEmpty()) return DEFAULT_WALL_HEIGHT_METERS
        val values = walls.map { it.heightMeters }.filter { it.isFinite() && it > 0f }.sorted()
        if (values.isEmpty()) return DEFAULT_WALL_HEIGHT_METERS
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) * 0.5f
    }

    private fun wallLength(wall: WallSegment): Float {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun Bitmap.downscale(maxSide: Int): Bitmap {
        val safeSide = max(MIN_CORRECTION_ANALYSIS_SIDE, maxAnalysisSideOrDefault(maxSide))
        val longest = max(width, height)
        if (longest <= safeSide) return this
        val ratio = safeSide.toFloat() / longest.toFloat()
        val targetWidth = max(1, (width * ratio).toInt())
        val targetHeight = max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun maxAnalysisSideOrDefault(value: Int): Int =
        if (value > 0) value.coerceAtMost(MAX_CORRECTION_ANALYSIS_SIDE) else DEFAULT_CORRECTION_ANALYSIS_SIDE

    private const val MIN_WALL_LENGTH_METERS = 0.18f
    private const val MIN_WALL_THICKNESS_METERS = 0.07f
    private const val MAX_WALL_THICKNESS_METERS = 0.60f
    private const val MAX_SINGLE_TRANSLATION_METERS = 2.50f
    private const val EDIT_ENVELOPE_MARGIN_METERS = 1.50f
    private const val MIN_REMAINING_WALLS = 4
    private const val DEFAULT_WALL_HEIGHT_METERS = 3.0f
    private const val MIN_CORRECTION_ANALYSIS_SIDE = 1400
    private const val DEFAULT_CORRECTION_ANALYSIS_SIDE = 2800
    private const val MAX_CORRECTION_ANALYSIS_SIDE = 3200
}
