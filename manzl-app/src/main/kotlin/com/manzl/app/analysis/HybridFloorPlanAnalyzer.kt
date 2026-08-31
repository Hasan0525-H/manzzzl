package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.FloorPlan

/**
 * Production-facing analyzer facade.
 *
 * 1) deterministic vision establishes measured topology;
 * 2) optional bundled on-device AI providers report semantic evidence;
 * 3) GeometryEvidenceFusion accepts only evidence that is geometrically plausible;
 * 4) the reconciled FloorPlan becomes the sole source of truth for 3D generation.
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
                // Reserve the final 14% for semantic inference and geometry reconciliation.
                val remapped = (update.percent * 0.86f).toInt().coerceIn(0, 86)
                progress.onUpdate(update.copy(percent = remapped))
            },
        )

        progress.onUpdate(AnalysisUpdate(89, "تحليل الأبواب والنوافذ والسلالم محلياً"))
        val semanticEvidence = ArrayList<SemanticEvidence>()
        semanticProviders.forEach { provider ->
            semanticEvidence += provider.analyze(bitmap, structural)
        }

        progress.onUpdate(AnalysisUpdate(94, "مطابقة نتائج الذكاء الاصطناعي مع هندسة المخطط"))
        val reconciled = GeometryEvidenceFusion.fuse(structural, semanticEvidence)

        progress.onUpdate(AnalysisUpdate(97, "مراجعة مسارات الحركة والفراغات"))
        val inferredDoors = DoorInferenceEngine.infer(reconciled)
        val enriched = reconciled.copy(
            doors = mergeDoors(reconciled, inferredDoors),
        )

        progress.onUpdate(AnalysisUpdate(100, "تم تجهيز المنزل للجولة"))
        return enriched
    }

    private fun mergeDoors(base: FloorPlan, inferred: List<com.manzl.app.model.DoorOpening>) =
        (base.doors + inferred)
            .sortedByDescending { it.confidence }
            .fold(ArrayList<com.manzl.app.model.DoorOpening>()) { result, candidate ->
                val duplicate = result.any { existing ->
                    val dx = existing.center.x - candidate.center.x
                    val dz = existing.center.z - candidate.center.z
                    dx * dx + dz * dz < 0.10f
                }
                if (!duplicate) result += candidate
                result
            }
}
