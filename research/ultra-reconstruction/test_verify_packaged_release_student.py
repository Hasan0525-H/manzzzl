from __future__ import annotations

import hashlib
import json
import pathlib
import tempfile
import unittest

import verify_packaged_release_student as verifier


class VerifyPackagedReleaseStudentTest(unittest.TestCase):
    def make_assets(self, root: pathlib.Path) -> tuple[pathlib.Path, dict, dict, dict]:
        assets = root / "models"
        assets.mkdir()
        model = assets / verifier.MODEL_NAME
        model.write_bytes(b"x" * 100_001)
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        size = model.stat().st_size

        training = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": verifier.MODEL_NAME,
            "sha256": digest,
            "bytes": size,
            "trainingSplit": "train",
            "modelSelectionSplit": "validation",
            "testSplitPresentAndVerified": True,
            "testUsedForTraining": False,
            "testUsedForModelSelection": False,
            "testUsedForValidationMetrics": False,
            "testReservedForFinalEvaluation": True,
            "realTrainingPreflightPassed": True,
            "releaseReady": False,
        }
        release = {
            "schema": 2,
            "pipeline": "manzl-real-student-release-evidence-bundle",
            "model": verifier.MODEL_NAME,
            "sha256": digest,
            "bytes": size,
            "trainingAttestationVerified": True,
            "candidateArtifactIntegrityPassed": True,
            "heldOutCorpusIdentityMatchedAcrossEvidence": True,
            "releaseCorpusScalePassed": True,
            "releaseCorpusScalePolicyVersion": 1,
            "releaseCorpusScaleRecomputedAtFinalize": True,
            "semanticMetricsExactHeldOutSampleCoverage": True,
            "semanticAcceptancePolicyLocked": True,
            "semanticAcceptancePolicyEvaluated": True,
            "relativeSemanticAcceptancePassed": True,
            "absoluteSemanticQualityPassed": True,
            "absoluteSemanticQualityFloorVersion": 1,
            "semanticEvidenceRecomputedAtFinalize": True,
            "semanticAcceptancePassed": True,
            "semanticHeldOutMeasurementCompleted": True,
            "geometryReleaseEvidencePassed": True,
            "allEvidenceBoundToExactModelDigest": True,
            "releaseEvidenceBundleComplete": True,
            "releaseReady": True,
            "blockingReason": None,
        }
        manifest = {
            "schema": 1,
            "runtime": "offline-only",
            "required": [
                {
                    "id": "manzl_reconstruction_student",
                    "path": f"models/{verifier.MODEL_NAME}",
                    "role": "test",
                    "status": "real-held-out-release-ready",
                    "sha256": digest,
                    "bytes": size,
                    "releaseReady": True,
                    "releaseEvidence": f"models/{verifier.RELEASE_NAME}",
                    "trainingProvenance": f"models/{verifier.TRAINING_NAME}",
                    "semanticQualityFloorVersion": 1,
                    "releaseCorpusScalePolicyVersion": 1,
                }
            ],
            "policy": {
                "networkAtRuntime": False,
                "paidApiFallback": False,
                "silentQualityDowngrade": False,
                "geometryAuthority": "source-raster-plus-deterministic-constraint-solver",
            },
        }
        (assets / verifier.TRAINING_NAME).write_text(json.dumps(training), encoding="utf-8")
        (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
        (assets / verifier.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
        return assets, training, release, manifest

    def test_exact_real_release_package_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, _, _ = self.make_assets(pathlib.Path(tmp))
            report = verifier.verify(assets)
            self.assertTrue(report["releaseReady"])
            self.assertTrue(report["releaseEvidenceBundleVerified"])
            self.assertTrue(report["releaseCorpusScalePassed"])
            self.assertTrue(report["semanticMetricsExactHeldOutSampleCoverage"])
            self.assertTrue(report["absoluteSemanticQualityPassed"])
            self.assertTrue(report["semanticEvidenceRecomputedAtFinalize"])

    def test_proposal_or_nonrelease_bundle_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release["releaseReady"] = False
            release["blockingReason"] = "proposal-only"
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseReady"):
                verifier.verify(assets)

    def test_legacy_release_without_absolute_proof_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release.pop("absoluteSemanticQualityPassed")
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "absoluteSemanticQualityPassed"):
                verifier.verify(assets)

    def test_release_without_corpus_scale_proof_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release.pop("releaseCorpusScalePassed")
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseCorpusScalePassed"):
                verifier.verify(assets)

    def test_wrong_corpus_scale_policy_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release["releaseCorpusScalePolicyVersion"] = 2
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseCorpusScalePolicyVersion"):
                verifier.verify(assets)

    def test_missing_exact_heldout_semantic_coverage_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release["semanticMetricsExactHeldOutSampleCoverage"] = False
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "semanticMetricsExactHeldOutSampleCoverage"):
                verifier.verify(assets)

    def test_wrong_absolute_floor_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, release, _ = self.make_assets(pathlib.Path(tmp))
            release["absoluteSemanticQualityFloorVersion"] = 0
            (assets / verifier.RELEASE_NAME).write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "absoluteSemanticQualityFloorVersion"):
                verifier.verify(assets)

    def test_manifest_must_bind_same_quality_floor_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, _, manifest = self.make_assets(pathlib.Path(tmp))
            manifest["required"][0]["semanticQualityFloorVersion"] = 2
            (assets / verifier.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "semanticQualityFloorVersion"):
                verifier.verify(assets)

    def test_manifest_must_bind_same_corpus_scale_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, _, manifest = self.make_assets(pathlib.Path(tmp))
            manifest["required"][0]["releaseCorpusScalePolicyVersion"] = 2
            (assets / verifier.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseCorpusScalePolicyVersion"):
                verifier.verify(assets)

    def test_different_model_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, _, _ = self.make_assets(pathlib.Path(tmp))
            (assets / verifier.MODEL_NAME).write_bytes(b"y" * 100_001)
            with self.assertRaisesRegex(RuntimeError, "sha256"):
                verifier.verify(assets)

    def test_manifest_cannot_claim_release_without_release_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, _, _, manifest = self.make_assets(pathlib.Path(tmp))
            manifest["required"][0]["status"] = "proposal-only"
            (assets / verifier.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "status"):
                verifier.verify(assets)

    def test_training_provenance_cannot_admit_test_use(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, training, _, _ = self.make_assets(pathlib.Path(tmp))
            training["testUsedForModelSelection"] = True
            (assets / verifier.TRAINING_NAME).write_text(json.dumps(training), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "testUsedForModelSelection"):
                verifier.verify(assets)


if __name__ == "__main__":
    unittest.main()
