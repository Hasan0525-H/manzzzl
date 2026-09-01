package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
 * Resolves cached source-space class observations against the latest deterministic wall graph.
 *
 * Neural stair labels are deliberately stricter than other semantics: they survive only when an
 * independent raster-derived OpenCV stair candidate agrees in center, orientation and approximate
 * footprint. The OpenCV observation is a *guard* here, not a second returned vote: Hybrid also runs
 * OpenCvStairEvidenceProvider as its own expert, so returning it again from this provider would double
 * count one detector and manufacture false ensemble confidence.
 */
internal object StudentSemanticEvidenceProvider : SemanticEvidenceProvider {
    private val arbitraryAngleStairGuard = OpenCvStairEvidenceProvider()

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> {
        val components = StudentSemanticEvidenceStore.get(bitmap)
        val rawStudent = if (components.isEmpty()) {
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

        if (rawStudent.none { it.kind == SemanticKind.STAIR }) return rawStudent

        val rasterStairs = arbitraryAngleStairGuard.analyze(bitmap, structuralPlan)
            .filter { it.kind == SemanticKind.STAIR }
            .filter { StairEvidenceGeometryGuard.isPlausible(structuralPlan, it) }

        // Non-stair student semantics retain their normal geometry guards. A student stair must have
        // an independent raster stair witness; if the witness is absent, the neural label abstains.
        return rawStudent.filter { evidence ->
            evidence.kind != SemanticKind.STAIR ||
                rasterStairs.any { raster -> compatibleStair(evidence, raster) }
        }
    }

    private fun compatibleStair(a: SemanticEvidence, b: SemanticEvidence): Boolean {
        val dx = a.center.x - b.center.x
        val dz = a.center.z - b.center.z
        if (sqrt(dx * dx + dz * dz) > MAX_STAIR_CENTER_DELTA_METERS) return false

        val aWidth = a.widthMeters
        val bWidth = b.widthMeters
        if (aWidth != null && bWidth != null && sizeRatio(aWidth, bWidth) < MIN_STAIR_SIZE_RATIO) return false
        val aLength = a.lengthMeters
        val bLength = b.lengthMeters
        if (aLength != null && bLength != null && sizeRatio(aLength, bLength) < MIN_STAIR_LENGTH_RATIO) return false

        val aRotation = a.rotationDegrees
        val bRotation = b.rotationDegrees
        return aRotation == null || bRotation == null ||
            axisAngleDifference(aRotation, bRotation) <= MAX_STAIR_AXIS_DELTA_DEGREES
    }

    private fun sizeRatio(a: Float, b: Float): Float {
        val high = max(abs(a), abs(b))
        val low = min(abs(a), abs(b))
        return if (high <= 0.000001f) 0f else low / high
    }

    private fun axisAngleDifference(a: Float, b: Float): Float {
        fun normalize(value: Float): Float {
            var result = value % 180f
            if (result < 0f) result += 180f
            return result
        }
        val delta = abs(normalize(a) - normalize(b))
        return min(delta, 180f - delta)
    }

    private const val MAX_STAIR_CENTER_DELTA_METERS = 0.72f
    private const val MIN_STAIR_SIZE_RATIO = 0.58f
    private const val MIN_STAIR_LENGTH_RATIO = 0.52f
    private const val MAX_STAIR_AXIS_DELTA_DEGREES = 18f
}
