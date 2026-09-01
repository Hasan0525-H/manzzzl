package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import java.util.WeakHashMap

/**
 * Reuses semantic observations produced during the student's structural inference instead of running
 * ONNX a second time. What is cached is deliberately source-raster observation, not already-resolved
 * geometry: OpenCV/MobileSAM may improve the wall graph after the student runs, so the provider maps
 * these observations against the *final* measured structural plan immediately before semantic fusion.
 * Weak bitmap keys prevent project-image lifetimes from being extended by this cache.
 */
internal object StudentSemanticEvidenceStore {
    private val lock = Any()
    private val componentsBySource = WeakHashMap<Bitmap, List<StudentSemanticComponentDecoder.Component>>()

    fun record(source: Bitmap, components: List<StudentSemanticComponentDecoder.Component>) {
        synchronized(lock) {
            if (components.isEmpty()) componentsBySource.remove(source)
            else componentsBySource[source] = components.toList()
        }
    }

    fun get(source: Bitmap): List<StudentSemanticComponentDecoder.Component> = synchronized(lock) {
        componentsBySource[source].orEmpty()
    }

    fun clear(source: Bitmap) {
        synchronized(lock) { componentsBySource.remove(source) }
    }
}

/**
 * Resolves cached source-space class observations against the latest deterministic wall graph and
 * contributes an independent arbitrary-angle OpenCV stair opinion in the same semantic phase.
 *
 * The OpenCV stair path is intentionally available even when the distilled student asset is absent.
 * This keeps stair reconstruction from silently falling back to the old axis-only detector, while
 * consensus/fusion still decides whether any observation is strong enough to enter canonical 3D.
 */
internal object StudentSemanticEvidenceProvider : SemanticEvidenceProvider {
    private val arbitraryAngleStairExpert = OpenCvStairEvidenceProvider()

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> {
        val components = StudentSemanticEvidenceStore.get(bitmap)
        val studentEvidence = if (components.isEmpty()) {
            emptyList()
        } else {
            val transform = PlanRasterTransform.forImage(structuralPlan, bitmap.width, bitmap.height)
            StudentSemanticEvidenceProjector.project(
                components = components,
                seed = structuralPlan,
                sourceTransform = transform,
                modelToSource = { x, y -> x to y },
                detailPass = false,
            )
        }

        val stairEvidence = arbitraryAngleStairExpert.analyze(bitmap, structuralPlan)
        if (studentEvidence.isEmpty()) return stairEvidence
        if (stairEvidence.isEmpty()) return studentEvidence
        return studentEvidence + stairEvidence
    }
}
