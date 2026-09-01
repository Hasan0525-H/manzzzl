from __future__ import annotations

import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import package_real_student_release as packager  # noqa: E402
import verify_packaged_release_student as verifier  # noqa: E402


class PackageRealStudentReleaseTest(unittest.TestCase):
    def make_fixture(self, root: pathlib.Path):
        candidate = root / "candidate"
        assets = root / "assets"
        candidate.mkdir()
        assets.mkdir()

        model = candidate / packager.MODEL_NAME
        model.write_bytes(b"real-student" * 10_001)
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        size = model.stat().st_size

        training = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": packager.MODEL_NAME,
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
        (candidate / "real-training-attestation.json").write_text(json.dumps(training), encoding="utf-8")

        release = {
            "schema": 2,
            "pipeline": "manzl-real-student-release-evidence-bundle",
            "model": packager.MODEL_NAME,
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
        release_path = root / "release.json"
        release_path.write_text(json.dumps(release), encoding="utf-8")

        manifest = {
            "schema": 1,
            "runtime": "offline-only",
            "required": [
                {
                    "id": "manzl_reconstruction_student",
                    "path": f"models/{packager.MODEL_NAME}",
                    "role": "multi-head floor-plan understanding distilled from the teacher ensemble",
                    "status": "to-be-generated",
                },
                {"id": "mobile_sam_encoder", "path": "models/mobile_sam_encoder.onnx", "status": "to-be-packaged"},
                {"id": "mobile_sam_decoder", "path": "models/mobile_sam_decoder.onnx", "status": "to-be-packaged"},
            ],
            "policy": {
                "networkAtRuntime": False,
                "paidApiFallback": False,
                "silentQualityDowngrade": False,
                "geometryAuthority": "source-raster-plus-deterministic-constraint-solver",
            },
        }
        (assets / packager.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
        (assets / packager.STALE_QUALITY_NAME).write_text("stale bootstrap evidence", encoding="utf-8")
        return candidate, release_path, assets, release

    def test_verified_release_is_packaged_and_manifest_committed(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, _ = self.make_fixture(pathlib.Path(tmp))
            report = packager.package_release(candidate, release_path, assets)
            self.assertTrue(report["releaseReady"])
            self.assertTrue(report["releaseCorpusScalePassed"])
            self.assertTrue(report["semanticMetricsExactHeldOutSampleCoverage"])
            self.assertTrue(report["absoluteSemanticQualityPassed"])
            self.assertTrue(report["semanticEvidenceRecomputedAtFinalize"])
            self.assertFalse((assets / packager.STALE_QUALITY_NAME).exists())
            self.assertTrue((assets / packager.MODEL_NAME).is_file())
            self.assertTrue((assets / packager.TRAINING_NAME).is_file())
            self.assertTrue((assets / packager.RELEASE_NAME).is_file())
            packaged_manifest = json.loads((assets / packager.MANIFEST_NAME).read_text(encoding="utf-8"))
            student = packaged_manifest["required"][0]
            self.assertEqual(student["semanticQualityFloorVersion"], 1)
            self.assertEqual(student["releaseCorpusScalePolicyVersion"], 1)
            self.assertTrue(verifier.verify(assets)["releaseReady"])

    def test_nonrelease_bundle_is_rejected_before_asset_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, release = self.make_fixture(pathlib.Path(tmp))
            manifest_before = (assets / packager.MANIFEST_NAME).read_bytes()
            release["releaseReady"] = False
            release["blockingReason"] = "semantic-failed"
            release_path.write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseReady"):
                packager.package_release(candidate, release_path, assets)
            self.assertEqual((assets / packager.MANIFEST_NAME).read_bytes(), manifest_before)
            self.assertFalse((assets / packager.MODEL_NAME).exists())

    def test_legacy_release_without_absolute_semantic_proof_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, release = self.make_fixture(pathlib.Path(tmp))
            release.pop("absoluteSemanticQualityPassed")
            release.pop("absoluteSemanticQualityFloorVersion")
            release.pop("semanticEvidenceRecomputedAtFinalize")
            release_path.write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "absoluteSemanticQualityPassed"):
                packager.package_release(candidate, release_path, assets)
            self.assertFalse((assets / packager.MODEL_NAME).exists())

    def test_legacy_release_without_corpus_scale_proof_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, release = self.make_fixture(pathlib.Path(tmp))
            release.pop("releaseCorpusScalePassed")
            release_path.write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "releaseCorpusScalePassed"):
                packager.package_release(candidate, release_path, assets)
            self.assertFalse((assets / packager.MODEL_NAME).exists())

    def test_release_without_exact_heldout_semantic_coverage_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, release = self.make_fixture(pathlib.Path(tmp))
            release["semanticMetricsExactHeldOutSampleCoverage"] = False
            release_path.write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "semanticMetricsExactHeldOutSampleCoverage"):
                packager.package_release(candidate, release_path, assets)
            self.assertFalse((assets / packager.MODEL_NAME).exists())

    def test_release_for_different_model_is_rejected_before_asset_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, release = self.make_fixture(pathlib.Path(tmp))
            release["sha256"] = "f" * 64
            release_path.write_text(json.dumps(release), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "sha256"):
                packager.package_release(candidate, release_path, assets)
            self.assertFalse((assets / packager.MODEL_NAME).exists())

    def test_test_contaminated_training_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, _ = self.make_fixture(pathlib.Path(tmp))
            training_path = candidate / "real-training-attestation.json"
            training = json.loads(training_path.read_text(encoding="utf-8"))
            training["testUsedForModelSelection"] = True
            training_path.write_text(json.dumps(training), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "testUsedForModelSelection"):
                packager.package_release(candidate, release_path, assets)

    def test_existing_release_requires_explicit_replace(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate, release_path, assets, _ = self.make_fixture(pathlib.Path(tmp))
            packager.package_release(candidate, release_path, assets)
            with self.assertRaisesRegex(RuntimeError, "already packaged"):
                packager.package_release(candidate, release_path, assets)
            self.assertTrue(packager.package_release(candidate, release_path, assets, replace=True)["releaseReady"])


if __name__ == "__main__":
    unittest.main()
