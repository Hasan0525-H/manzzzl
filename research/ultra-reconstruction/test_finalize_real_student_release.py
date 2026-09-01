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

import finalize_real_student_release as finalizer  # noqa: E402
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
            "releaseReady": False,
        }
        (candidate / "real-training-attestation.json").write_text(
            json.dumps(training), encoding="utf-8"
        )
        return candidate, digest, size

    def write_evidence_bundle(
        self,
        root: pathlib.Path,
        splits: pathlib.Path,
        digest: str,
        size: int,
    ) -> tuple[pathlib.Path, pathlib.Path]:
        preflight = verify_real_training_inputs.verify(splits)
        fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
        samples = preflight["testSamples"]
        groups = preflight["testSourceGroups"]

        semantic = {
            "schema": 2,
            "pipeline": "manzl-private-real-student-final-test",
            "model": "manzl_reconstruction_student.onnx",
            "sha256": digest,
            "bytes": size,
            "testSamples": samples,
            "testSourceGroups": groups,
            "testSetFingerprint": fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "testMetrics": {"mean_iou": 0.82},
            "testUsedForTraining": False,
            "testUsedForModelSelection": False,
            "testUsedForValidationMetrics": False,
            "testUsedForFinalEvaluation": True,
            "heldOutTestIntegrityVerified": True,
            "semanticTestCompleted": True,
            "semanticAcceptancePolicyEvaluated": False,
            "semanticAcceptancePassed": False,
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
        return semantic_path, geometry_path

    def test_matching_evidence_bundle_is_complete_but_release_stays_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            semantic, geometry = self.write_evidence_bundle(root, splits, digest, size)

            report = finalizer.finalize(splits, candidate, semantic, geometry)

            self.assertTrue(report["releaseEvidenceBundleComplete"])
            self.assertTrue(report["candidateArtifactIntegrityPassed"])
            self.assertTrue(report["heldOutCorpusIdentityMatchedAcrossEvidence"])
            self.assertTrue(report["allEvidenceBoundToExactModelDigest"])
            self.assertFalse(report["semanticAcceptancePolicyLocked"])
            self.assertFalse(report["releaseReady"])
            self.assertEqual(report["blockingReason"], "semantic-acceptance-policy-not-locked")

    def test_semantic_attestation_from_different_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            semantic, geometry = self.write_evidence_bundle(root, splits, digest, size)
            payload = json.loads(semantic.read_text(encoding="utf-8"))
            payload["sha256"] = "f" * 64
            semantic.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "sha256"):
                finalizer.finalize(splits, candidate, semantic, geometry)

    def test_different_held_out_corpus_fingerprint_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            semantic, geometry = self.write_evidence_bundle(root, splits, digest, size)
            payload = json.loads(geometry.read_text(encoding="utf-8"))
            payload["testSetFingerprint"] = "e" * 64
            geometry.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "testSetFingerprint"):
                finalizer.finalize(splits, candidate, semantic, geometry)

    def test_failed_geometry_gate_cannot_be_combined_as_release_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = self.make_splits(root)
            candidate, digest, size = self.make_candidate(root)
            semantic, geometry = self.write_evidence_bundle(root, splits, digest, size)
            payload = json.loads(geometry.read_text(encoding="utf-8"))
            payload["releaseGeometryEvidencePassed"] = False
            geometry.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "releaseGeometryEvidencePassed"):
                finalizer.finalize(splits, candidate, semantic, geometry)


if __name__ == "__main__":
    unittest.main()
