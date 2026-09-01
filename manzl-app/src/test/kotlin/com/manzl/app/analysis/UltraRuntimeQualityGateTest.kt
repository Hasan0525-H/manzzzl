package com.manzl.app.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraRuntimeQualityGateTest {

    @Test
    fun `missing student blocks ultra reconstruction even when MobileSAM is present`() {
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = setOf(
                    UltraModelCatalog.MOBILE_SAM_ENCODER,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
                integrityVerifiedAssets = setOf(
                    UltraModelCatalog.MOBILE_SAM_ENCODER,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
            )
        )

        assertFalse(decision.ready)
        assertTrue(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT in decision.missingAssets)
        assertTrue(decision.messageArabic.orEmpty().contains("Manzl Reconstruction Student"))
    }

    @Test
    fun `all files present still block proposal-only student`() {
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
                integrityVerifiedAssets = setOf(
                    UltraModelCatalog.MOBILE_SAM_ENCODER,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
            )
        )

        assertFalse(decision.ready)
        assertTrue(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT in decision.unapprovedAssets)
        assertTrue(decision.messageArabic.orEmpty().contains("مخططات حقيقية"))
    }

    @Test
    fun `release approved student with every verified digest allows ultra reconstruction`() {
        val student = UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
                releaseApprovedAssets = setOf(student),
                integrityVerifiedAssets = UltraModelCatalog.requiredIntegrityAssets,
            )
        )

        assertTrue(decision.ready)
        assertTrue(decision.missingAssets.isEmpty())
        assertTrue(decision.unapprovedAssets.isEmpty())
        assertTrue(decision.integrityFailedAssets.isEmpty())
        assertTrue(decision.messageArabic == null)
    }

    @Test
    fun `approved student still blocks when MobileSAM encoder digest is not verified`() {
        val student = UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
                releaseApprovedAssets = setOf(student),
                integrityVerifiedAssets = setOf(
                    student,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
            )
        )

        assertFalse(decision.ready)
        assertTrue(UltraModelCatalog.MOBILE_SAM_ENCODER in decision.integrityFailedAssets)
        assertTrue(decision.messageArabic.orEmpty().contains("MobileSAM Encoder"))
        assertTrue(decision.messageArabic.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `approved metadata with mismatched student hash blocks ultra reconstruction`() {
        val student = UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
                releaseApprovedAssets = setOf(student),
                integrityVerifiedAssets = setOf(
                    UltraModelCatalog.MOBILE_SAM_ENCODER,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
            )
        )

        assertFalse(decision.ready)
        assertTrue(student in decision.integrityFailedAssets)
        assertTrue(decision.messageArabic.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `missing ONNX runtime blocks without cloud fallback`() {
        val student = UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = false,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
                releaseApprovedAssets = setOf(student),
                integrityVerifiedAssets = UltraModelCatalog.requiredIntegrityAssets,
            )
        )

        assertFalse(decision.ready)
        assertTrue(decision.messageArabic.orEmpty().contains("ONNX Runtime"))
    }

    @Test
    fun `release evidence parser reads corpus scale and immutable semantic quality fields`() {
        val json = """
            {
              "schema": 2,
              "pipeline": "manzl-real-student-release-evidence-bundle",
              "releaseCorpusScalePassed": true,
              "releaseCorpusScalePolicyVersion": 1,
              "releaseCorpusScaleRecomputedAtFinalize": true,
              "semanticMetricsExactHeldOutSampleCoverage": true,
              "relativeSemanticAcceptancePassed": true,
              "absoluteSemanticQualityPassed": true,
              "absoluteSemanticQualityFloorVersion": 1,
              "semanticEvidenceRecomputedAtFinalize": true,
              "semanticAcceptancePassed": true,
              "releaseReady": true,
              "blockingReason": null
            }
        """.trimIndent()

        assertTrue(OnnxAssetModelRepository.jsonInt(json, "schema") == 2)
        assertTrue(
            OnnxAssetModelRepository.jsonString(json, "pipeline") ==
                "manzl-real-student-release-evidence-bundle"
        )
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "releaseCorpusScalePassed") == true)
        assertTrue(
            OnnxAssetModelRepository.jsonInt(json, "releaseCorpusScalePolicyVersion") ==
                UltraModelCatalog.RELEASE_CORPUS_SCALE_POLICY_VERSION
        )
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "releaseCorpusScaleRecomputedAtFinalize") == true)
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "semanticMetricsExactHeldOutSampleCoverage") == true)
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "relativeSemanticAcceptancePassed") == true)
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "absoluteSemanticQualityPassed") == true)
        assertTrue(
            OnnxAssetModelRepository.jsonInt(json, "absoluteSemanticQualityFloorVersion") ==
                UltraModelCatalog.SEMANTIC_QUALITY_FLOOR_VERSION
        )
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "semanticEvidenceRecomputedAtFinalize") == true)
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "semanticAcceptancePassed") == true)
        assertTrue(OnnxAssetModelRepository.jsonBoolean(json, "releaseReady") == true)
        assertTrue(OnnxAssetModelRepository.jsonNull(json, "blockingReason"))
    }

    @Test
    fun `legacy release metadata cannot satisfy current corpus and semantic contracts`() {
        val legacy = """
            {
              "schema": 2,
              "pipeline": "manzl-real-student-release-evidence-bundle",
              "semanticAcceptancePassed": true,
              "releaseReady": true,
              "blockingReason": null
            }
        """.trimIndent()

        assertFalse(OnnxAssetModelRepository.jsonBoolean(legacy, "releaseCorpusScalePassed") == true)
        assertFalse(
            OnnxAssetModelRepository.jsonInt(legacy, "releaseCorpusScalePolicyVersion") ==
                UltraModelCatalog.RELEASE_CORPUS_SCALE_POLICY_VERSION
        )
        assertFalse(OnnxAssetModelRepository.jsonBoolean(legacy, "releaseCorpusScaleRecomputedAtFinalize") == true)
        assertFalse(OnnxAssetModelRepository.jsonBoolean(legacy, "semanticMetricsExactHeldOutSampleCoverage") == true)
        assertFalse(OnnxAssetModelRepository.jsonBoolean(legacy, "absoluteSemanticQualityPassed") == true)
        assertFalse(
            OnnxAssetModelRepository.jsonInt(legacy, "absoluteSemanticQualityFloorVersion") ==
                UltraModelCatalog.SEMANTIC_QUALITY_FLOOR_VERSION
        )
        assertFalse(OnnxAssetModelRepository.jsonBoolean(legacy, "semanticEvidenceRecomputedAtFinalize") == true)
    }

    @Test
    fun `proposal quality is not equivalent to final release evidence`() {
        val proposal = """
            {
              "schema": 5,
              "proposalOnly": true,
              "realPlanBenchmarkPassed": false,
              "releaseReady": false
            }
        """.trimIndent()

        assertFalse(
            OnnxAssetModelRepository.jsonString(proposal, "pipeline") ==
                "manzl-real-student-release-evidence-bundle"
        )
        assertFalse(OnnxAssetModelRepository.jsonBoolean(proposal, "releaseReady") == true)
    }
}
