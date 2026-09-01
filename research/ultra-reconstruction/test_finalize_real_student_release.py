from __future__ import annotations

import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import evaluate_real_student_test  # noqa: E402
import finalize_real_student_release as finalizer  # noqa: E402
import real_semantic_policy  # noqa: E402
import verify_real_training_inputs  # noqa: E402


class FinalizeRealStudentReleaseTest(unittest.TestCase):
    def write_sample(self, split: pathlib.Path, digest: str, group: str, value: int) -> None:
        split.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(
            split / f"sample-{digest}.npz",
            image=np.full((12, 16, 3), value, dtype=np.uint8),
            semantic=np.zeros((12, 16), dtype=np.int64),
            supervision_mask=np.ones((12, 16), dtype=np.float32),
            source_group=np.asarray(group),
        )

    def make_splits(self, root: pathlib.Path) -> pathlib.Path:
        splits = root / "splits"
        splits.mkdir()
        self.write_sample(splits / "train", "1" * 32, "private:" + "a" * 32, 10)
        self.write_sample(splits / "validation", "2" * 32, "private:" + "b" * 32, 20)
        self.write_sample(splits / "test", "3" * 32, "private:" + "c" * 32, 30)
        report = {
            "schema": 2,
            "pipeline": "private-real-consensus-to-held-out-splits",
            "samples": 3,
            "samplesBySplit": {"train": 1, "validation": 1, "test": 1},
            "sourceGroupsBySplit": {"train": 1, "validation": 1, "test": 1},
            "splitPolicy": {
                "unit": "source_group",
                "deterministic": True,
                "validationFraction": 0.15,
                "testFraction": 0.15,
                "trainFraction": 0.70,
                "sameFamilyCrossSplitAllowed": False,
            },
            "splitAssignmentsReverifiedFromSaltAndPolicy": True,
            "privatePathsStored": False,
            "rawRasterHashesStored": False,
            "opaqueOutputFilenames": True,
            "transactionalMaterialization": True,
            "existingOutputOverwritten": False,
            "testReservedForFinalEvaluation": True,
            "releaseReady": False,
        }
        (splits / "materialization_report.json").write_text(json.dumps(report), encoding="utf-8")
        return splits

    def metrics(self, domain: str) -> dict:
        per_class = {}
        names = ("background",) + real_semantic_policy.CRITICAL_CLASSES
        for index, name in enumerate(names):
            tp = 900 + index * 5
            fp = 35 + index
            fn = 45 + index
            per_class[name] = {
                "present": True,
                "iou": tp / (tp + fp + fn),
                "precision": tp / (tp + fp),
                "recall": tp / (tp + fn),
                "supportPixels": tp + fn,
                "tp": tp,
                "fp": fp,
                "fn": fn,
                "intersection": tp,
                "union": tp + fp + fn,
            }
        return {
            "schema": 2,
            "domain": domain,
            "samples": 24,
            "inputSize": 512,
            "semantic": {"meanIoU": 0.90, "perClass": per_class},
            "corners": {
                "runtimeThreshold": 0.56,
                "thresholdMatchesAndroidCornerSnap": True,
                "precision": 0.91,
                "recall": 0.90,
                "f1": 0.905,
                "meanAbsoluteError": 0.04,
                "supportPixels": 1000,
                "evaluatedPixels": 9000,
                "tp": 900,
                "fp": 89,
                "fn": 100,
            },
            "orientation": {
                "signInvariant": True,
                "meanAbsCosine": 0.98,
                "meanAngularErrorDegrees": 6.0,
                "supportPixels": 4000,
            },
            "releaseReady": False,
        }

    def make_candidate(self, root: pathlib.Path) -> tuple[pathlib.Path, str, int]:
        candidate = root / "candidate"
        candidate.mkdir()
        model = candidate / "manzl_reconstruction_student.onnx"
        model.write_bytes(b"same-real-release-candidate")
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        size = model.stat().st_size
        training = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": model.name,
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
            "validationEvaluation": self.metrics("private-real-validation"),
            "releaseReady": False,
        }
        (candidate / "real-training-attestation.json").write_text(json.dumps(training), encoding="utf-8")
        return candidate, digest, size

    def write_evidence_bundle(
        self,
        root: pathlib.Path,
        splits: pathlib.Path,
        candidate: pathlib.Path,
        digest: str,
        size: int,
    ) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
        preflight = verify_real_training_inputs.verify(splits)
        fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
        samples = preflight["testSamples"]
        groups = preflight["testSourceGroups"]

        locked = real_semantic_policy.build_policy(candidate)
        policy_path = root / "semantic-policy.json"
        policy_path.write_text(json.dumps(locked, indent=2, sort_keys=True), encoding="utf-8")
        policy_sha = hashlib.sha256(policy_path.read_bytes()).hexdigest()
        test_metrics = self.metrics("private-real-held-out-test")
        acceptance = real_semantic_policy.evaluate_metrics(locked, test_metrics)
        absolute = evaluate_real_student_test.absolute_semantic_quality(test_metrics)
        self.assertTrue(acceptance["semanticAcceptancePassed"])
        self.assertTrue(absolute["absoluteSemanticQualityPassed"])

        semantic = {
            "schema": 3,
            "pipeline": "manzl-private-real-student-final-test",
            "model": "manzl_reconstruction_student.onnx",
            "sha256": digest,
            "bytes": size,
            "testSamples": samples,
            "testSourceGroups": groups,
            "testSetFingerprint": fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "testMetrics": test_metrics,
            "semanticPolicySha256": policy_sha,
            "semanticAcceptanceEvaluation": acceptance,
            "absoluteSemanticQualityEvaluation": absolute,
            "relativeSemanticAcceptancePassed": True,
            "absoluteSemanticQualityPassed": True,
            "testUsedForTraining": False,
            "testUsedForModelSelection": False,
            "testUsedForValidationMetrics": False,
            "testUsedForFinalEvaluation": True,
            "heldOutTestIntegrityVerified": True,
            "semanticTestCompleted": True,
            "semanticAcceptancePolicyLockedBeforeTest": True,
            "semanticAcceptancePolicyEvaluated": True,
            "semanticAcceptancePassed": True,
            "geometryGatesEvaluatedByThisStep": False,
            "releaseReady": False,
        }
        semantic_path = root / "semantic.json"
        semantic_path.write_text(json.dumps(semantic), encoding="utf-8")

        geometry = {
            "schema": 3,
            "pipeline": "manzl-held-out-real-plan-end-to-end-geometry-release-gate",
            "model": "manzl_reconstruction_student.onnx",
            "modelSha256": digest,
            "modelBytes": size,
            "candidateTrainingAttestationVerified": True,
            "geometryEvidenceBoundToExactModelDigest": True,
            "testSetFingerprint": fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "testSamples": samples,
            "evidenceSamples": samples,
            "exactHeldOutSampleCoverage": True,
            "runtimeThresholdsDuplicated": False,
            "allGeometryFidelityPassed": True,
            "allGeometryQualityGatesPassed": True,
            "allReconstructionReadinessGatesPassed": True,
            "allEndToEnd2dTo3dGeometryGatesPassed": True,
            "releaseGeometryEvidencePassed": True,
            "releaseReady": False,
        }
        geometry_path = root / "geometry.json"
        geometry_path.write_text(json.dumps(geometry), encoding="utf-8")
        return policy_path, semantic_path, geometry_path

    def test_matching_evidence_bundle_becomes_model_release_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)

            report = finalizer.finalize(splits, candidate, policy_path, semantic, geometry)

            self.assertTrue(report["releaseEvidenceBundleComplete"])
            self.assertTrue(report["semanticAcceptancePolicyLocked"])
            self.assertTrue(report["relativeSemanticAcceptancePassed"])
            self.assertTrue(report["absoluteSemanticQualityPassed"])
            self.assertTrue(report["semanticEvidenceRecomputedAtFinalize"])
            self.assertEqual(report["absoluteSemanticQualityFloorVersion"], 1)
            self.assertTrue(report["semanticAcceptancePassed"])
            self.assertTrue(report["geometryReleaseEvidencePassed"])
            self.assertTrue(report["releaseReady"])
            self.assertIsNone(report["blockingReason"])

    def test_semantic_attestation_from_different_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)
            payload = json.loads(semantic.read_text(encoding="utf-8"))
            payload["sha256"] = "f" * 64
            semantic.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "sha256"):
                finalizer.finalize(splits, candidate, policy_path, semantic, geometry)

    def test_failed_semantic_acceptance_flag_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)
            payload = json.loads(semantic.read_text(encoding="utf-8"))
            payload["semanticAcceptancePassed"] = False
            semantic.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "semanticAcceptancePassed"):
                finalizer.finalize(splits, candidate, policy_path, semantic, geometry)

    def test_forged_absolute_pass_cannot_hide_weak_raw_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)
            payload = json.loads(semantic.read_text(encoding="utf-8"))
            floor = evaluate_real_student_test.ABSOLUTE_CLASS_FLOORS["door"]["recall"]
            payload["testMetrics"]["semantic"]["perClass"]["door"]["recall"] = floor - 0.10
            payload["absoluteSemanticQualityPassed"] = True
            payload["semanticAcceptancePassed"] = True
            semantic.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "recomputed absolute semantic quality failed"):
                finalizer.finalize(splits, candidate, policy_path, semantic, geometry)

    def test_stored_semantic_evaluation_must_match_recomputation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)
            payload = json.loads(semantic.read_text(encoding="utf-8"))
            payload["absoluteSemanticQualityEvaluation"]["checks"]["class:door:recall"]["actual"] = 1.0
            semantic.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match recomputed"):
                finalizer.finalize(splits, candidate, policy_path, semantic, geometry)

    def test_different_held_out_corpus_fingerprint_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            policy_path, semantic, geometry = self.write_evidence_bundle(root, splits, candidate, digest, size)
            payload = json.loads(geometry.read_text(encoding="utf-8"))
            payload["testSetFingerprint"] = "e" * 64
            geometry.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "testSetFingerprint"):
                finalizer.finalize(splits, candidate, policy_path, semantic, geometry)


if __name__ == "__main__":
    unittest.main()
