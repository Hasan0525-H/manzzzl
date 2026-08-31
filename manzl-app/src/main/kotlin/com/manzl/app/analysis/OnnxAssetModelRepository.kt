package com.manzl.app.analysis

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable

/**
 * Central catalog for the high-quality reconstruction models that are allowed to ship in the APK.
 *
 * Heavy teacher checkpoints (Raster2Seq/RoomFormer) are intentionally not listed here: they are used
 * in the free development/training pipeline and distilled into a mobile-safe Manzl student model.
 * This keeps runtime completely offline while preserving the benefit of multiple stronger teachers.
 */
internal object UltraModelCatalog {
    const val MANZL_RECONSTRUCTION_STUDENT = "models/manzl_reconstruction_student.onnx"
    const val MOBILE_SAM_ENCODER = "models/mobile_sam_encoder.onnx"
    const val MOBILE_SAM_DECODER = "models/mobile_sam_decoder.onnx"

    val requiredForUltraRuntime = listOf(
        MANZL_RECONSTRUCTION_STUDENT,
        MOBILE_SAM_ENCODER,
        MOBILE_SAM_DECODER,
    )
}

internal data class UltraModelAvailability(
    val onnxRuntimeReady: Boolean,
    val presentAssets: Set<String>,
) {
    val ultraRuntimeReady: Boolean
        get() = onnxRuntimeReady && UltraModelCatalog.requiredForUltraRuntime.all { it in presentAssets }

    val missingAssets: List<String>
        get() = UltraModelCatalog.requiredForUltraRuntime.filterNot { it in presentAssets }
}

/**
 * Loads bundled ONNX assets only. There is deliberately no URL/download path here; the release APK
 * must work in airplane mode and can never silently switch to a paid/cloud inference service.
 */
internal class OnnxAssetModelRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val environment: OrtEnvironment? = runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    private val sessions = LinkedHashMap<String, OrtSession>()

    fun availability(): UltraModelAvailability {
        val present = UltraModelCatalog.requiredForUltraRuntime
            .filterTo(LinkedHashSet()) { assetExists(it) }
        return UltraModelAvailability(
            onnxRuntimeReady = environment != null,
            presentAssets = present,
        )
    }

    @Synchronized
    fun session(assetPath: String): OrtSession? {
        sessions[assetPath]?.let { return it }
        val env = environment ?: return null
        val model = runCatching {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (model.isEmpty()) return null

        val options = OrtSession.SessionOptions()
        return try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(true)
            options.setDeterministicCompute(true)
            options.setIntraOpNumThreads(recommendedIntraOpThreads())
            env.createSession(model, options).also { sessions[assetPath] = it }
        } catch (_: Exception) {
            null
        } finally {
            // ORT copies native session configuration during construction. The options object owns
            // only its native configuration handle and is safe to close after createSession returns.
            runCatching { options.close() }
        }
    }

    fun assetExists(assetPath: String): Boolean = runCatching {
        appContext.assets.open(assetPath).use { true }
    }.getOrDefault(false)

    @Synchronized
    override fun close() {
        sessions.values.forEach { session -> runCatching { session.close() } }
        sessions.clear()
        // OrtEnvironment.close is a no-op on current ORT and the singleton may be shared elsewhere.
    }

    private fun recommendedIntraOpThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
