package com.manzl.app.analysis

/**
 * Fail-closed runtime policy for the high-quality 2D -> house path.
 *
 * The project explicitly forbids silent quality downgrade. If the distilled reconstruction student or
 * MobileSAM assets are absent, the app must not quietly fall back to the legacy line scanner and then
 * present that result as an Ultra reconstruction. The deterministic/CV pipeline remains useful as an
 * adjudicator and diagnostic fallback, but it is not sufficient on its own for user-visible 3D.
 *
 * Presence is also not approval. A bundled student remains blocked until its quality attestation says
 * that held-out real-plan validation passed and the attested SHA-256 matches the packaged ONNX bytes.
 */
internal object UltraRuntimeQualityGate {

    data class Decision(
        val ready: Boolean,
        val missingAssets: List<String>,
        val unapprovedAssets: List<String>,
        val integrityFailedAssets: List<String>,
        val messageArabic: String?,
    )

    fun evaluate(availability: UltraModelAvailability?): Decision {
        if (availability == null) {
            return Decision(
                ready = false,
                missingAssets = UltraModelCatalog.requiredForUltraRuntime,
                unapprovedAssets = emptyList(),
                integrityFailedAssets = emptyList(),
                messageArabic = "أوقفت التحويل قبل بناء 3D لأن محرك Ultra المحلي لم يتهيأ. لن أستخدم المسار القديم وحده وأعرض منزلاً بجودة منخفضة على أنه نتيجة نهائية.",
            )
        }
        if (!availability.onnxRuntimeReady) {
            return Decision(
                ready = false,
                missingAssets = availability.missingAssets,
                unapprovedAssets = availability.unapprovedAssets,
                integrityFailedAssets = availability.integrityFailedAssets,
                messageArabic = "أوقفت التحويل لأن ONNX Runtime المحلي غير جاهز على هذا الجهاز. لا يوجد انتقال إلى API مدفوع أو سحابة، ولا يوجد تخفيض صامت للجودة.",
            )
        }
        if (availability.missingAssets.isNotEmpty()) {
            val friendly = availability.missingAssets.map(::friendlyAssetName)
            return Decision(
                ready = false,
                missingAssets = availability.missingAssets,
                unapprovedAssets = availability.unapprovedAssets,
                integrityFailedAssets = availability.integrityFailedAssets,
                messageArabic = "أوقفت التحويل لأن حزمة الجودة الفائقة غير مكتملة محلياً: ${friendly.joinToString("، ")}. لن أبني بيتاً بالماسح التقليدي وحده؛ يجب اكتمال نماذج Ultra أولاً.",
            )
        }
        if (availability.unapprovedAssets.isNotEmpty()) {
            return Decision(
                ready = false,
                missingAssets = emptyList(),
                unapprovedAssets = availability.unapprovedAssets,
                integrityFailedAssets = availability.integrityFailedAssets,
                messageArabic = "أوقفت التحويل لأن نموذج Manzl الموجود ما زال تجريبياً ولم يجتز اعتماد مخططات حقيقية مستقلة. وجود ملف ONNX وحده لا يكفي؛ لن أسمح له بإنتاج منزل 3D نهائي قبل نجاح اختبار real-plan بدون تسريب بيانات.",
            )
        }
        if (availability.integrityFailedAssets.isNotEmpty()) {
            return Decision(
                ready = false,
                missingAssets = emptyList(),
                unapprovedAssets = emptyList(),
                integrityFailedAssets = availability.integrityFailedAssets,
                messageArabic = "أوقفت التحويل لأن بصمة نموذج Manzl المرفق لا تطابق بصمة SHA-256 المعتمدة في تقرير الجودة. قد يكون الملف مختلفاً عن النموذج الذي تم اختباره، لذلك لن أستخدمه لبناء 3D.",
            )
        }
        if (!availability.ultraRuntimeReady) {
            return Decision(
                ready = false,
                missingAssets = availability.missingAssets,
                unapprovedAssets = availability.unapprovedAssets,
                integrityFailedAssets = availability.integrityFailedAssets,
                messageArabic = "أوقفت التحويل لأن محرك Ultra لم يجتز جميع بوابات الجاهزية المحلية. لن يحدث تخفيض صامت إلى مسار أقل دقة.",
            )
        }
        return Decision(
            ready = true,
            missingAssets = emptyList(),
            unapprovedAssets = emptyList(),
            integrityFailedAssets = emptyList(),
            messageArabic = null,
        )
    }

    fun currentDecision(): Decision = evaluate(UltraReconstructionRuntime.modelAvailabilityOrNull())

    private fun friendlyAssetName(path: String): String = when (path) {
        UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT -> "Manzl Reconstruction Student"
        UltraModelCatalog.MOBILE_SAM_ENCODER -> "MobileSAM Encoder"
        UltraModelCatalog.MOBILE_SAM_DECODER -> "MobileSAM Decoder"
        else -> path.substringAfterLast('/')
    }
}

internal class UltraRuntimeUnavailableException(message: String) : IllegalStateException(message)
