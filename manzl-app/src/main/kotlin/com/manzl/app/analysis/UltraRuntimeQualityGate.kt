package com.manzl.app.analysis

/**
 * Fail-closed runtime policy for the high-quality 2D -> house path.
 *
 * The project explicitly forbids silent quality downgrade. If the distilled reconstruction student or
 * MobileSAM assets are absent, the app must not quietly fall back to the legacy line scanner and then
 * present that result as an Ultra reconstruction. The deterministic/CV pipeline remains useful as an
 * adjudicator and diagnostic fallback, but it is not sufficient on its own for user-visible 3D.
 */
internal object UltraRuntimeQualityGate {

    data class Decision(
        val ready: Boolean,
        val missingAssets: List<String>,
        val messageArabic: String?,
    )

    fun evaluate(availability: UltraModelAvailability?): Decision {
        if (availability == null) {
            return Decision(
                ready = false,
                missingAssets = UltraModelCatalog.requiredForUltraRuntime,
                messageArabic = "أوقفت التحويل قبل بناء 3D لأن محرك Ultra المحلي لم يتهيأ. لن أستخدم المسار القديم وحده وأعرض منزلاً بجودة منخفضة على أنه نتيجة نهائية.",
            )
        }
        if (!availability.onnxRuntimeReady) {
            return Decision(
                ready = false,
                missingAssets = availability.missingAssets,
                messageArabic = "أوقفت التحويل لأن ONNX Runtime المحلي غير جاهز على هذا الجهاز. لا يوجد انتقال إلى API مدفوع أو سحابة، ولا يوجد تخفيض صامت للجودة.",
            )
        }
        if (!availability.ultraRuntimeReady) {
            val friendly = availability.missingAssets.map(::friendlyAssetName)
            return Decision(
                ready = false,
                missingAssets = availability.missingAssets,
                messageArabic = "أوقفت التحويل لأن حزمة الجودة الفائقة غير مكتملة محلياً: ${friendly.joinToString("، ")}. لن أبني بيتاً بالماسح التقليدي وحده؛ يجب اكتمال نماذج Ultra أولاً.",
            )
        }
        return Decision(
            ready = true,
            missingAssets = emptyList(),
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
