package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.sqrt

/**
 * Production-facing analyzer facade.
 *
 * 1) deterministic vision establishes measured topology and metric scale;
 * 2) optional bundled on-device AI providers report semantic evidence;
 * 3) GeometryEvidenceFusion accepts only evidence that is geometrically plausible;
 * 4) deterministic door/room topology closes remaining gaps;
 * 5) the reconciled FloorPlan becomes the sole source of truth for 3D generation.
 *
 * No provider is allowed to require a network connection in the release build.
 */
internal class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
    private val semanticProviders: List<SemanticEvidenceProvider> = emptyList(),
) : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan {
        val structural = structuralAnalyzer.analyze(
            bitmap = bitmap,
            progress = ProgressSink { update ->
                // Reserve the final stages for semantic inference and topology reconciliation.
                val remapped = (update.percent * 0.82f).toInt().coerceIn(0, 82)
                progress.onUpdate(update.copy(percent = remapped))
            },
        )

        progress.onUpdate(AnalysisUpdate(86, "تحليل الأبواب والنوافذ والسلالم محلياً"))
        val semanticEvidence = ArrayList<SemanticEvidence>()
        semanticProviders.forEach { provider ->
            semanticEvidence += provider.analyze(bitmap, structural)
        }

        progress.onUpdate(AnalysisUpdate(91, "مطابقة نتائج الذكاء الاصطناعي مع هندسة المخطط"))
        val reconciled = GeometryEvidenceFusion.fuse(structural, semanticEvidence)

        progress.onUpdate(AnalysisUpdate(95, "مراجعة فتحات الأبواب ومسارات الحركة"))
        val inferredDoors = DoorInferenceEngine.infer(reconciled)
        val withDoors = reconciled.copy(
            doors = mergeDoors(reconciled.doors, inferredDoors),
        )

        progress.onUpdate(AnalysisUpdate(98, "اكتشاف حدود الغرف والأسقف"))
        val inferredRooms = RoomInferenceEngine.infer(withDoors)
        val enriched = withDoors.copy(
            rooms = mergeRooms(withDoors.rooms, inferredRooms),
        )

        progress.onUpdate(AnalysisUpdate(100, "تم تجهيز المنزل للجولة"))
        return enriched
    }

    private fun mergeDoors(base: List<DoorOpening>, inferred: List<DoorOpening>): List<DoorOpening> =
        (base + inferred)
            .sortedByDescending { it.confidence }
            .fold(ArrayList<DoorOpening>()) { result, candidate ->
                val duplicate = result.any { existing ->
                    val dx = existing.center.x - candidate.center.x
                    val dz = existing.center.z - candidate.center.z
                    dx * dx + dz * dz < DOOR_DUPLICATE_DISTANCE_SQ
                }
                if (!duplicate) result += candidate
                result
            }

    private fun mergeRooms(base: List<RoomRegion>, inferred: List<RoomRegion>): List<RoomRegion> {
        val result = ArrayList<RoomRegion>()
        for (candidate in (base + inferred).sortedByDescending { it.confidence }) {
            val center = candidate.centroidOrNull() ?: continue
            val duplicate = result.any { existing ->
                val existingCenter = existing.centroidOrNull() ?: return@any false
                val dx = existingCenter.x - center.x
                val dz = existingCenter.z - center.z
                val distance = sqrt(dx * dx + dz * dz)
                distance <= ROOM_CENTER_DUPLICATE_METERS &&
                    areaRatio(existing, candidate) >= ROOM_DUPLICATE_MIN_AREA_RATIO
            }
            if (!duplicate) result += candidate
        }
        return result
    }

    private fun RoomRegion.centroidOrNull(): Vec2? {
        if (polygon.isEmpty()) return null
        return Vec2(
            x = polygon.sumOf { it.x.toDouble() }.toFloat() / polygon.size,
            z = polygon.sumOf { it.z.toDouble() }.toFloat() / polygon.size,
        )
    }

    private fun areaRatio(a: RoomRegion, b: RoomRegion): Float {
        val areaA = boundingArea(a)
        val areaB = boundingArea(b)
        if (areaA <= 0f || areaB <= 0f) return 0f
        return minOf(areaA, areaB) / maxOf(areaA, areaB)
    }

    private fun boundingArea(room: RoomRegion): Float {
        if (room.polygon.isEmpty()) return 0f
        val minX = room.polygon.minOf { it.x }
        val maxX = room.polygon.maxOf { it.x }
        val minZ = room.polygon.minOf { it.z }
        val maxZ = room.polygon.maxOf { it.z }
        return (maxX - minX).coerceAtLeast(0f) * (maxZ - minZ).coerceAtLeast(0f)
    }

    companion object {
        private const val DOOR_DUPLICATE_DISTANCE_SQ = 0.10f
        private const val ROOM_CENTER_DUPLICATE_METERS = 0.32f
        private const val ROOM_DUPLICATE_MIN_AREA_RATIO = 0.72f
    }
}
