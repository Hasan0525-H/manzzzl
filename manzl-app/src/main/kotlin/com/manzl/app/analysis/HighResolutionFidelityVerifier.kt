package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Re-verifies an already extracted plan against a denser copy of the same original raster.
 *
 * This is deliberately separate from the high-resolution extractor: both the normal-pass geometry
 * and the high-resolution retry are judged against the same dense source evidence before selection.
 * A low-resolution PASS therefore cannot hide details that become visible at 2800/3200 px.
 */
internal object HighResolutionFidelityVerifier {

    suspend fun verify(
        source: Bitmap,
        plan: FloorPlan,
        analysisSide: Int,
    ): FloorPlan = withContext(Dispatchers.Default) {
        val working = source.downscaleForVerification(analysisSide)
        try {
            val structural = StructuralRasterMask.classify(working)
            val fidelity = GeometryFidelityEvaluator.evaluate(
                structuralMask = structural.mask,
                imageWidth = working.width,
                imageHeight = working.height,
                plan = plan,
            )
            plan.copy(
                analysisConfidence = (
                    plan.analysisConfidence.coerceIn(0f, 1f) * 0.45f +
                        fidelity.score.coerceIn(0f, 1f) * 0.55f
                    ).coerceIn(0f, 0.99f),
                geometryFidelity = fidelity,
            )
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun Bitmap.downscaleForVerification(maxSide: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide.toFloat() / longest.toFloat()
        val targetWidth = max(1, (width * ratio).toInt())
        val targetHeight = max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }
}
