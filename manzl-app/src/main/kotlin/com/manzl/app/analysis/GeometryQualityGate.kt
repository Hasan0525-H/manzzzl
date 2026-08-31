package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus

/**
 * Typed rejection that preserves the measured 2D geometry for the review overlay.
 * The plan is diagnostic evidence only; callers must not bypass the gate and build 3D from it.
 */
internal class GeometryQualityRejectedException(
    val plan: FloorPlan,
    message: String,
) : IllegalStateException(message)

/**
 * Hard release/runtime guard against building a convincing 3D house from weak 2D geometry.
 *
 * Visual polish, semantic AI and rendering are intentionally downstream from this gate. In strict
 * mode only an independently verified PASS may enter the 3D synthesis pipeline.
 */
internal object GeometryQualityGate {

    fun isReadyFor3d(plan: FloorPlan): Boolean =
        plan.geometryFidelity.status == GeometryFidelityStatus.PASS

    fun rejectionMessageArabic(plan: FloorPlan): String? {
        val report = plan.geometryFidelity
        if (report.status == GeometryFidelityStatus.PASS) return null
        val score = (report.score * 100f).toInt().coerceIn(0, 100)
        val coverage = (report.wallCoverage * 100f).toInt().coerceIn(0, 100)
        val precision = (report.wallPrecision * 100f).toInt().coerceIn(0, 100)
        val endpoints = (report.endpointSupport * 100f).toInt().coerceIn(0, 100)
        return when (report.status) {
            GeometryFidelityStatus.REVIEW_REQUIRED ->
                "أوقفت بناء 3D لأن مطابقة الجدران تحتاج مراجعة: الجودة $score%، تغطية الجدران $coverage%، دقة مواضعها $precision%، دعم النهايات $endpoints%. افتح مراجعة التطابق لترى الجدران فوق المخطط قبل أي 3D."

            GeometryFidelityStatus.BLOCKED ->
                "أوقفت بناء 3D لأن الهندسة المستخرجة لا تطابق المخطط بما يكفي: الجودة $score%، تغطية الجدران $coverage%، دقة مواضعها $precision%، دعم النهايات $endpoints%. لن يتم إنتاج بيت خاطئ؛ افتح مراجعة التطابق لمعرفة مواضع النقص."

            GeometryFidelityStatus.UNKNOWN ->
                "أوقفت بناء 3D لأن مطابقة الهندسة مع المخطط لم تُتحقق بعد. لا يتم تجاوز بوابة الجودة بدون قياس."

            GeometryFidelityStatus.PASS -> null
        }
    }
}
