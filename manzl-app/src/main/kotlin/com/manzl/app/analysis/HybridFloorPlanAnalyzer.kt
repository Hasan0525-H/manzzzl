package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.FloorPlan

/**
 * Production-facing analyzer façade.
 *
 * Stage 1 uses deterministic vision for topology. Stage 2 enriches the structural result with
 * doorway semantics. The interface is intentionally ready for a future bundled on-device model:
 * neural evidence can be fused here without letting a generative system replace measured geometry.
 */
class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
) : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan {
        val structural = structuralAnalyzer.analyze(
            bitmap = bitmap,
            progress = ProgressSink { update ->
                // Reserve the final 8% for topology/semantic reconciliation.
                val remapped = (update.percent * 0.92f).toInt().coerceIn(0, 92)
                progress.onUpdate(update.copy(percent = remapped))
            },
        )

        progress.onUpdate(AnalysisUpdate(94, "استنتاج فتحات الأبواب وربط الغرف"))
        val inferredDoors = DoorInferenceEngine.infer(structural)

        progress.onUpdate(AnalysisUpdate(97, "مراجعة قابلية المشي بين الفراغات"))
        val enriched = structural.copy(
            doors = mergeDoors(structural, inferredDoors),
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
