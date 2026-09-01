package com.manzl.app.analysis

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.security.MessageDigest

/**
 * Central catalog for the high-quality reconstruction models that are allowed to ship in the APK.
 *
 * Heavy teacher checkpoints (Raster2Seq/RoomFormer) are intentionally not listed here: they are used
 * in the free development/training pipeline and distilled into a mobile-safe Manzl student model.
 * This keeps runtime completely offline while preserving the benefit of multiple stronger teachers.
 */
internal object UltraModelCatalog {
    const val MANZL_RECONSTRUCTION_STUDENT = "models/manzl_reconstruction_student.onnx"
    const val MANZL_RECONSTRUCTION_STUDENT_QUALITY = "models/manzl_reconstruction_student.quality.json"
    const val MOBILE_SAM_ENCODER = "models/mobile_sam_encoder.onnx"
    const val MOBILE_SAM_DECODER = "models/mobile_sam_decoder.onnx"

    val requiredForUltraRuntime = listOf(
        MANZL_RECONSTRUCTION_STUDENT,
        MOBILE_SAM_ENCODER,
        MOBILE_SAM_DECODER,
    )

    /** Models whose mere presence is insufficient: they need explicit measured release evidence. */
    val releaseAttestedAssets = setOf(MANZL_RECONSTRUCTION_STUDENT)
}

internal data class UltraModelAvailability(
    val onnxRuntimeReady: Boolean,
    val presentAssets: Set<String>,
    val releaseApprovedAssets: Set<String> = emptySet(),
    val integrityVerifiedAssets: Set<String> = emptySet(),
) {
    val ultraRuntimeReady: Boolean
        get() = onnxRuntimeReady &&
            UltraModelCatalog.requiredForUltraRuntime.all { it in presentAssets } &&
            UltraModelCatalog.releaseAttestedAssets.all {
                it in releaseApprovedAssets && it in integrityVerifiedAssets
            }

    val missingAssets: List<String>
        get() = UltraModelCatalog.requiredForUltraRuntime.filterNot { it in presentAssets }

    val unapprovedAssets: List<String>
        get() = UltraModelCatalog.releaseAttestedAssets.filter {
            it in presentAssets && it !in releaseApprovedAssets
        }

    val integrityFailedAssets: List<String>
        get() = UltraModelCatalog.releaseAttestedAssets.filter {
            it in presentAssets && it in releaseApprovedAssets && it !in integrityVerifiedAssets
        }
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
        val studentAttestation = studentReleaseAttestation()
        return UltraModelAvailability(
            onnxRuntimeReady = environment != null,
            presentAssets = present,
            releaseApprovedAssets = buildSet {
                if (studentAttestation.releaseApproved) add(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            },
            integrityVerifiedAssets = buildSet {
                if (studentAttestation.integrityVerified) add(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            },
        )
    }

    internal fun environmentOrNull(): OrtEnvironment? = environment

    @Synchronized
    fun session(assetPath: String): OrtSession? {
        sessions[assetPath]?.let { return it }
        val env = environment ?: return null
        val model = readAssetBytes(assetPath) ?: return null
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

    /**
     * Runtime must independently enforce the same release contract as CI. A file copied into assets is
     * not enough to become Ultra: the student needs a real-plan release attestation and the attested
     * SHA-256 must match the exact ONNX bytes packaged in this APK.
     */
    private fun studentReleaseAttestation(): StudentReleaseAttestation {
        val model = readAssetBytes(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            ?: return StudentReleaseAttestation.NOT_APPROVED
        val quality = readAssetText(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT_QUALITY)
            ?: return StudentReleaseAttestation.NOT_APPROVED

        val expectedSha = JSON_SHA256_REGEX.find(quality)?.groupValues?.getOrNull(1)?.lowercase()
        val actualSha = sha256(model)
        val integrityVerified = expectedSha != null && expectedSha == actualSha

        // releaseReady can also occur inside generatedValidation. The final/root attestation is emitted
        // after nested validation data, so the last occurrence is the authoritative release decision.
        val releaseReady = lastJsonBoolean(quality, "releaseReady") == true
        val proposalOnly = lastJsonBoolean(quality, "proposalOnly")
        val realPlanBenchmarkPassed = lastJsonBoolean(quality, "realPlanBenchmarkPassed") == true
        val releaseApproved = releaseReady && proposalOnly == false && realPlanBenchmarkPassed

        return StudentReleaseAttestation(
            releaseApproved = releaseApproved,
            integrityVerified = integrityVerified,
        )
    }

    private fun readAssetBytes(assetPath: String): ByteArray? = runCatching {
        appContext.assets.open(assetPath).use { it.readBytes() }
    }.getOrNull()

    private fun readAssetText(assetPath: String): String? = runCatching {
        appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    @Synchronized
    override fun close() {
        sessions.values.forEach { session -> runCatching { session.close() } }
        sessions.clear()
        // OrtEnvironment.close is a no-op on current ORT and the singleton may be shared elsewhere.
    }

    private fun recommendedIntraOpThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private data class StudentReleaseAttestation(
        val releaseApproved: Boolean,
        val integrityVerified: Boolean,
    ) {
        companion object {
            val NOT_APPROVED = StudentReleaseAttestation(
                releaseApproved = false,
                integrityVerified = false,
            )
        }
    }

    companion object {
        private val JSON_SHA256_REGEX = Regex("\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\"")

        internal fun lastJsonBoolean(json: String, key: String): Boolean? {
            val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)")
            return regex.findAll(json).lastOrNull()?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
