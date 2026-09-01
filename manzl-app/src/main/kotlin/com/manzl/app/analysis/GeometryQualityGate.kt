package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssueKind
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
 * mode only an independently verified PASS may enter the 3D synthesis pipeline. Aggregate PASS is
 * not enough when a localized region contains a severe wall mismatch, and it is also insufficient
 * when trusted wall axes stop short of a non-parallel wall leaving a physical crack in the 3D shell.
 */
internal object GeometryQualityGate {

    fun isReadyFor3d(plan: FloorPlan): Boolean =
        plan.geometryFidelity.status == GeometryFidelityStatus.PASS &&
            criticalLocalMismatch(plan) == null &&
            WallTopologyIntegrity.findNearMissJunctions(plan).isEmpty()

    /**
     * Review UI must never display a green PASS for a plan that this gate will reject. The aggregate
     * fidelity metrics stay untouched, but PASS is downgraded to REVIEW_REQUIRED in the diagnostic
     * copy whenever a severe local mismatch or topology crack blocks 3D. Canonical geometry is not
     * changed and this method never upgrades a status.
     */
    fun planForReview(plan: FloorPlan): FloorPlan {
        if (plan.geometryFidelity.status != GeometryFidelityStatus.PASS || isReadyFor3d(plan)) return plan
        return plan.copy(
            geometryFidelity = plan.geometryFidelity.copy(
                status = GeometryFidelityStatus.REVIEW_REQUIRED,
            )
        )
    }

    fun rejectionMessageArabic(plan: FloorPlan): String? {
        val report = plan.geometryFidelity
        val localMismatch = criticalLocalMismatch(plan)
        val topologyIssue = WallTopologyIntegrity.findNearMissJunctions(plan).firstOrNull()
        if (
            report.status == GeometryFidelityStatus.PASS &&
            localMismatch == null &&
            topologyIssue == null
        ) return null

        val score = (report.score * 100f).toInt().coerceIn(0, 100)
        val coverage = (report.wallCoverage * 100f).toInt().coerceIn(0, 100)
        val precision = (report.wallPrecision * 100f).toInt().coerceIn(0, 100)
        val endpoints = (report.endpointSupport * 100f).toInt().coerceIn(0, 100)

        if (report.status == GeometryFidelityStatus.PASS && localMismatch != null) {
            val severity = (localMismatch.severity * 100f).toInt().coerceIn(0, 100)
            val problem = when (localMismatch.kind) {
                GeometryFidelityIssueKind.MISSING_SOURCE -> "جزء من جدار موجود في المخطط لكنه مفقود من الهندسة"
                GeometryFidelityIssueKind.EXTRA_GEOMETRY -> "جدار مستخرج لا يملك دعماً كافياً في المخطط"
            }
            return "أوقفت بناء 3D رغم نجاح المتوسط العام لأن هناك خطأ هندسياً موضعياً شديداً: $problem، شدة الاختلاف $severity%. المتوسطات: الجودة $score%، التغطية $coverage%، الدقة $precision%، دعم النهايات $endpoints%. افتح مراجعة التطابق وصحح المنطقة المحددة؛ لا يتم السماح لخطأ محلي أن يختبئ داخل متوسط مرتفع."
        }

        if (report.status == GeometryFidelityStatus.PASS && topologyIssue != null) {
            val gapCm = (topologyIssue.physicalGapMeters * 100f).toInt().coerceAtLeast(1)
            return "أوقفت بناء 3D رغم نجاح مطابقة الصورة لأن هناك وصلة جدران غير مكتملة: نهاية جدار موثوق تتوقف قبل جدار آخر بحوالي $gapCm سم بعد احتساب سماكات الجدار. هذا قد يفتح شقاً في المنزل أو يقسم الغرفة خطأ. راجع المنطقة وصحح نقطة الالتقاء؛ لا يتم إغلاقها تلقائياً إذا لم يثبت الرسم ذلك."
        }

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

    private fun criticalLocalMismatch(plan: FloorPlan) = plan.geometryFidelity.issues
        .asSequence()
        .filter { issue ->
            val area =
                (issue.rightFraction - issue.leftFraction).coerceAtLeast(0f) *
                    (issue.bottomFraction - issue.topFraction).coerceAtLeast(0f)
            area >= MIN_CRITICAL_REGION_FRACTION
        }
        .filter { issue ->
            when (issue.kind) {
                GeometryFidelityIssueKind.MISSING_SOURCE -> issue.severity >= MAX_MISSING_SOURCE_DEFICIT
                GeometryFidelityIssueKind.EXTRA_GEOMETRY -> issue.severity >= MAX_EXTRA_GEOMETRY_DEFICIT
            }
        }
        .maxByOrNull { it.severity }

    private const val MIN_CRITICAL_REGION_FRACTION = 0.003f
    private const val MAX_MISSING_SOURCE_DEFICIT = 0.55f
    private const val MAX_EXTRA_GEOMETRY_DEFICIT = 0.62f
}
