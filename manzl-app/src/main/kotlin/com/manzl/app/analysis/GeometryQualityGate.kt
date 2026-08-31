package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus

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
        return when (report.status) {
            GeometryFidelityStatus.REVIEW_REQUIRED ->
                "أوقفت بناء 3D لأن مطابقة الجدران تحتاج مراجعة: الجودة $score%، تغطية الجدران $coverage%، دقة مواضعها $precision%. لن أبني بيتاً تقريبياً قبل تحسين الهندسة."

            GeometryFidelityStatus.BLOCKED ->
                "أوقفت بناء 3D لأن الهندسة المستخرجة لا تطابق المخطط بما يكفي: الجودة $score%، تغطية الجدران $coverage%، دقة مواضعها $precision%. استخدم مخططاً أوضح أو انتظر أداة التصحيح الهندسي بدلاً من إنتاج بيت خاطئ."

            GeometryFidelityStatus.UNKNOWN ->
                "أوقفت بناء 3D لأن مطابقة الهندسة مع المخطط لم تُتحقق بعد. لا يتم تجاوز بوابة الجودة بدون قياس."

            GeometryFidelityStatus.PASS -> null
        }
    }
}
