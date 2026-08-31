package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import java.util.WeakHashMap

/**
 * Reuses semantic observations produced during the student's structural inference instead of running
 * the ONNX model a second time later in the semantic pipeline. Weak bitmap keys prevent project-image
 * lifetimes from being extended by this cache.
 */
internal object StudentSemanticEvidenceStore {
    private val lock = Any()
    private val evidenceBySource = WeakHashMap<Bitmap, List<SemanticEvidence>>()

    fun record(source: Bitmap, evidence: List<SemanticEvidence>) {
        synchronized(lock) {
            if (evidence.isEmpty()) evidenceBySource.remove(source)
            else evidenceBySource[source] = evidence.toList()
        }
    }

    fun get(source: Bitmap): List<SemanticEvidence> = synchronized(lock) {
        evidenceBySource[source].orEmpty()
    }

    fun clear(source: Bitmap) {
        synchronized(lock) { evidenceBySource.remove(source) }
    }
}

/** Evidence provider backed by the already-computed student inference for this exact bitmap. */
internal object StudentSemanticEvidenceProvider : SemanticEvidenceProvider {
    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> =
        StudentSemanticEvidenceStore.get(bitmap)
}
