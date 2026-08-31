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
 * 2) deterministic door/room topology creates a geometry baseline;
 * 3) bundled on-device semantic providers may label rooms or suggest stairs/openings;
 * 4) GeometryEvidenceFusion accepts only evidence that is geometrically plausible;
 * 5) deterministic topology is re-run after fusion and becomes the sole source of truth for 3D;
 * 6) door swing symbols may enrich an accepted opening but never create or move one;
 * 7) door/window conflicts are resolved from symbol-specific evidence before final room topology.
 *
 * No provider is allowed to require a network connection in the release build.
 */
internal class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
    private val semanticProviders: List<SemanticEvidenceProvider> = listOf(
        RoomLabelEvidenceProvider(),
        StairPatternEvidenceProvider(),
        WindowSymbolEvidenceProvider(),
    ),
) : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan {
        val structural = structuralAnalyzer.analyze(
            bitmap = bitmap,
            progress = ProgressSink { update ->
                val remapped = (update.percent * 0.78f).toInt().coerceIn(0, 78)
                progress.onUpdate(update.copy(percent = remapped))
            },
        )

        progress.onUpdate(AnalysisUpdate(82, "بناء خط أساس للأبواب والغرف"))
        val baselineDoors = DoorInferenceEngine.infer(structural)
        val withDoors = structural.copy(
            doors = mergeDoors(structural.doors, baselineDoors),
        )
        val baselineRooms = RoomInferenceEngine.infer(withDoors)
        val baseline = withDoors.copy(
            rooms = mergeRooms(withDoors.rooms, baselineRooms),
        )

        progress.onUpdate(AnalysisUpdate(87, "قراءة الغرف والسلالم ورموز النوافذ محلياً"))
        val semanticEvidence = ArrayList<SemanticEvidence>()
        semanticProviders.forEach { provider ->
            val providerPlan = if (provider is WindowSymbolEvidenceProvider) {
                // Geometry-only door gaps are not sufficient evidence to suppress a real double-line
                // window symbol. Door/window conflicts are decided later after swing-arc enrichment.
                baseline.copy(doors = emptyList())
            } else {
                baseline
            }
            semanticEvidence += provider.analyze(bitmap, providerPlan)
        }

        progress.onUpdate(AnalysisUpdate(92, "مطابقة الدلالات مع هندسة المخطط"))
        val reconciled = GeometryEvidenceFusion.fuse(baseline, semanticEvidence)

        progress.onUpdate(AnalysisUpdate(95, "مراجعة مرشحات الأبواب والنوافذ"))
        val inferredDoors = DoorInferenceEngine.infer(reconciled)
        val finalDoors = mergeDoors(reconciled.doors, inferredDoors)
        val withFinalDoors = reconciled.copy(doors = finalDoors)

        progress.onUpdate(AnalysisUpdate(97, "قراءة مفصلات واتجاه فتح الأبواب من رموز المخطط"))
        val withDoorDynamics = withFinalDoors.copy(
            doors = DoorSwingArcDetector.enrich(bitmap, withFinalDoors),
        )
        val withClassifiedOpenings = OpeningSemanticReconciler.reconcile(withDoorDynamics)

        progress.onUpdate(AnalysisUpdate(98, "مراجعة حدود الغرف والأسقف"))
        val inferredRooms = RoomInferenceEngine.infer(withClassifiedOpenings)
        val enriched = withClassifiedOpenings.copy(
            rooms = mergeRooms(withClassifiedOpenings.rooms, inferredRooms),
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
            val index = result.indexOfFirst { existing ->
                val existingCenter = existing.centroidOrNull() ?: return@indexOfFirst false
                val dx = existingCenter.x - center.x
                val dz = existingCenter.z - center.z
                val distance = sqrt(dx * dx + dz * dz)
                distance <= ROOM_CENTER_DUPLICATE_METERS &&
                    areaRatio(existing, candidate) >= ROOM_DUPLICATE_MIN_AREA_RATIO
            }
            if (index < 0) {
                result += candidate
            } else if (result[index].label.isNullOrBlank() && !candidate.label.isNullOrBlank()) {
                val existing = result[index]
                result[index] = existing.copy(
                    label = candidate.label,
                    confidence = maxOf(existing.confidence, candidate.confidence),
                )
            }
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
