package com.manzl.app.analysis

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.security.MessageDigest

/**
 * Central catalog for the high-quality reconstruction models that are allowed to ship in the APK.
 *
 * Heavy teacher checkpoints are development-time only and are distilled into the mobile student.
 * Runtime remains completely offline and must never infer release readiness from file presence alone.
 */
internal object UltraModelCatalog {
    const val MANZL_RECONSTRUCTION_STUDENT = "models/manzl_reconstruction_student.onnx"
    const val MANZL_RECONSTRUCTION_STUDENT_RELEASE = "models/manzl_reconstruction_student.release.json"
    const val MANZL_RECONSTRUCTION_STUDENT_TRAINING = "models/manzl_reconstruction_student.training.json"
    const val MANZL_MODEL_MANIFEST = "models/manifest.json"
    const val MOBILE_SAM_ENCODER = "models/mobile_sam_encoder.onnx"
    const val MOBILE_SAM_DECODER = "models/mobile_sam_decoder.onnx"

    const val MOBILE_SAM_ENCODER_SHA256 =
        "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749"
    const val MOBILE_SAM_DECODER_SHA256 =
        "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d"
    const val SEMANTIC_QUALITY_FLOOR_VERSION = 1

    val requiredForUltraRuntime = listOf(
        MANZL_RECONSTRUCTION_STUDENT,
        MOBILE_SAM_ENCODER,
        MOBILE_SAM_DECODER,
    )

    val releaseAttestedAssets = setOf(MANZL_RECONSTRUCTION_STUDENT)
    val requiredIntegrityAssets = requiredForUltraRuntime.toSet()
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
            UltraModelCatalog.releaseAttestedAssets.all { it in releaseApprovedAssets } &&
            UltraModelCatalog.requiredIntegrityAssets.all { it in integrityVerifiedAssets }

    val missingAssets: List<String>
        get() = UltraModelCatalog.requiredForUltraRuntime.filterNot { it in presentAssets }

    val unapprovedAssets: List<String>
        get() = UltraModelCatalog.releaseAttestedAssets.filter {
            it in presentAssets && it !in releaseApprovedAssets
        }

    val integrityFailedAssets: List<String>
        get() = UltraModelCatalog.requiredIntegrityAssets.filter {
            it in presentAssets && it !in integrityVerifiedAssets
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
        val release = studentFinalReleaseAttestation()
        val integrity = buildSet {
            if (release.integrityVerified) add(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            if (
                sha256Asset(UltraModelCatalog.MOBILE_SAM_ENCODER) ==
                UltraModelCatalog.MOBILE_SAM_ENCODER_SHA256
            ) {
                add(UltraModelCatalog.MOBILE_SAM_ENCODER)
            }
            if (
                sha256Asset(UltraModelCatalog.MOBILE_SAM_DECODER) ==
                UltraModelCatalog.MOBILE_SAM_DECODER_SHA256
            ) {
                add(UltraModelCatalog.MOBILE_SAM_DECODER)
            }
        }
        return UltraModelAvailability(
            onnxRuntimeReady = environment != null,
            presentAssets = present,
            releaseApprovedAssets = buildSet {
                if (release.releaseApproved) add(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            },
            integrityVerifiedAssets = integrity,
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
            runCatching { options.close() }
        }
    }

    fun assetExists(assetPath: String): Boolean = runCatching {
        appContext.assets.open(assetPath).use { true }
    }.getOrDefault(false)

    /**
     * Mirrors the Python APK boundary gate at runtime. A proposal/bootstrap quality file is never an
     * approval source. Only the final held-out release bundle, bound to the exact ONNX digest, immutable
     * semantic quality floor, and release-ready runtime manifest, may promote the student into Ultra.
     */
    private fun studentFinalReleaseAttestation(): StudentReleaseAttestation {
        val model = readAssetBytes(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT)
            ?: return StudentReleaseAttestation.NOT_APPROVED
        val release = readAssetText(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT_RELEASE)
            ?: return StudentReleaseAttestation.NOT_APPROVED
        val manifest = readAssetText(UltraModelCatalog.MANZL_MODEL_MANIFEST)
            ?: return StudentReleaseAttestation.NOT_APPROVED

        val actualSha = sha256(model)
        val releaseSha = jsonString(release, "sha256")?.lowercase()
        val manifestSha = jsonString(manifest, "sha256")?.lowercase()
        val integrityVerified = releaseSha == actualSha && manifestSha == actualSha

        val releaseApproved =
            jsonInt(release, "schema") == 2 &&
            jsonString(release, "pipeline") == "manzl-real-student-release-evidence-bundle" &&
            jsonString(release, "model") == "manzl_reconstruction_student.onnx" &&
            jsonBoolean(release, "trainingAttestationVerified") == true &&
            jsonBoolean(release, "candidateArtifactIntegrityPassed") == true &&
            jsonBoolean(release, "heldOutCorpusIdentityMatchedAcrossEvidence") == true &&
            jsonBoolean(release, "semanticAcceptancePolicyLocked") == true &&
            jsonBoolean(release, "semanticAcceptancePolicyEvaluated") == true &&
            jsonBoolean(release, "relativeSemanticAcceptancePassed") == true &&
            jsonBoolean(release, "absoluteSemanticQualityPassed") == true &&
            jsonInt(release, "absoluteSemanticQualityFloorVersion") ==
                UltraModelCatalog.SEMANTIC_QUALITY_FLOOR_VERSION &&
            jsonBoolean(release, "semanticEvidenceRecomputedAtFinalize") == true &&
            jsonBoolean(release, "semanticAcceptancePassed") == true &&
            jsonBoolean(release, "semanticHeldOutMeasurementCompleted") == true &&
            jsonBoolean(release, "geometryReleaseEvidencePassed") == true &&
            jsonBoolean(release, "allEvidenceBoundToExactModelDigest") == true &&
            jsonBoolean(release, "releaseEvidenceBundleComplete") == true &&
            jsonBoolean(release, "releaseReady") == true &&
            jsonNull(release, "blockingReason") &&
            jsonString(manifest, "status") == "real-held-out-release-ready" &&
            jsonBoolean(manifest, "releaseReady") == true &&
            jsonInt(manifest, "semanticQualityFloorVersion") ==
                UltraModelCatalog.SEMANTIC_QUALITY_FLOOR_VERSION &&
            jsonString(manifest, "releaseEvidence") == "models/manzl_reconstruction_student.release.json" &&
            jsonString(manifest, "trainingProvenance") == "models/manzl_reconstruction_student.training.json"

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

    private fun sha256Asset(assetPath: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.assets.open(assetPath).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    @Synchronized
    override fun close() {
        sessions.values.forEach { session -> runCatching { session.close() } }
        sessions.clear()
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
        internal fun jsonBoolean(json: String, key: String): Boolean? {
            val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)")
            return regex.find(json)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
        }

        internal fun jsonString(json: String, key: String): String? {
            val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            return regex.find(json)?.groupValues?.getOrNull(1)
        }

        internal fun jsonInt(json: String, key: String): Int? {
            val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
            return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        internal fun jsonNull(json: String, key: String): Boolean {
            val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*null(?=\\s*[,}])")
            return regex.containsMatchIn(json)
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .toHex()

        private fun ByteArray.toHex(): String =
            joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
