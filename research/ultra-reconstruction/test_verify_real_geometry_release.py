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

import verify_real_geometry_release as verifier  # noqa: E402


class VerifyRealGeometryReleaseTest(unittest.TestCase):
    def write_sample(self, split: pathlib.Path, digest: str, group: str, value: int) -> pathlib.Path:
        split.mkdir(parents=True, exist_ok=True)
        path = split / f"sample-{digest}.npz"
        image = np.full((16, 20, 3), value, dtype=np.uint8)
        semantic = np.zeros((16, 20), dtype=np.int64)
        np.savez_compressed(
            path,
            image=image,
            semantic=semantic,
            supervision_mask=np.ones((16, 20), dtype=np.float32),
            source_group=np.asarray(group),
        )
        return path

    def make_splits(self, root: pathlib.Path) -> str:
        self.write_sample(root / "train", "1" * 32, "private:" + "a" * 32, 10)
        self.write_sample(root / "validation", "2" * 32, "private:" + "b" * 32, 20)
        test_id = "sample-" + "3" * 32
        self.write_sample(root / "test", "3" * 32, "private:" + "c" * 32, 30)
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
        (root / "materialization_report.json").write_text(json.dumps(report), encoding="utf-8")
        return test_id

    def make_candidate(self, root: pathlib.Path) -> tuple[pathlib.Path, str]:
        candidate = root / "candidate"
        candidate.mkdir()
        model = candidate / "manzl_reconstruction_student.onnx"
        model.write_bytes(b"verified-real-plan-onnx-candidate")
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        attestation = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": model.name,
            "sha256": digest,
            "bytes": model.stat().st_size,
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
            json.dumps(attestation), encoding="utf-8"
        )
        return candidate, digest

    def passing_evidence(self, sample_id: str, model_sha256: str) -> dict:
        return {
            "schema": 2,
            "pipeline": "manzl-runtime-end-to-end-geometry-gates",
            "sampleId": sample_id,
            "modelSha256": model_sha256,
            "sourcePathsStored": False,
            "sourceFilenamesStored": False,
            "rawRasterHashesStored": False,
            "runtimeThresholdsDuplicated": False,
            "fidelityStatus": "PASS",
            "fidelityScore": 0.91,
            "wallCoverage": 0.88,
            "wallPrecision": 0.93,
            "endpointSupport": 0.95,
            "geometryFidelityPass": True,
            "geometryQualityGatePassed": True,
            "reconstructionReadinessGatePassed": True,
            "topologyNearMissCount": 0,
            "unresolvedOpeningCount": 0,
            "unsupportedVerticalVoidCount": 0,
            "unsupportedRoomBoundaryCount": 0,
            "trustedRoomCoverage": 0.94,
            "trustedRoomCount": 1,
            "endToEnd2dTo3dGeometryGatesPassed": True,
            "releaseReady": False,
        }

    def write_evidence(
        self,
        root: pathlib.Path,
        sample_id: str,
        model_sha256: str,
        payload: dict | None = None,
    ) -> pathlib.Path:
        root.mkdir(parents=True, exist_ok=True)
        path = root / f"{sample_id}.geometry.json"
        path.write_text(
            json.dumps(payload or self.passing_evidence(sample_id, model_sha256)),
            encoding="utf-8",
        )
        return path

    def test_exact_held_out_geometry_evidence_is_bound_to_verified_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            candidate, digest = self.make_candidate(base)
            evidence = base / "evidence"
            self.write_evidence(evidence, sample_id, digest)

            report = verifier.verify(splits, evidence, candidate)

            self.assertTrue(report["releaseGeometryEvidencePassed"])
            self.assertTrue(report["exactHeldOutSampleCoverage"])
            self.assertTrue(report["geometryEvidenceBoundToExactModelDigest"])
            self.assertTrue(report["candidateTrainingAttestationVerified"])
            self.assertEqual(report["modelSha256"], digest)
            self.assertTrue(report["allEndToEnd2dTo3dGeometryGatesPassed"])
            self.assertFalse(report["runtimeThresholdsDuplicated"])
            self.assertFalse(report["releaseReady"])

    def test_missing_held_out_sample_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            self.make_splits(splits)
            candidate, _ = self.make_candidate(base)
            evidence = base / "evidence"
            evidence.mkdir()

            with self.assertRaisesRegex(RuntimeError, "contains no samples"):
                verifier.verify(splits, evidence, candidate)

    def test_runtime_gate_failure_is_rejected_without_python_threshold_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            candidate, digest = self.make_candidate(base)
            evidence = base / "evidence"
            payload = self.passing_evidence(sample_id, digest)
            payload["geometryQualityGatePassed"] = False
            payload["endToEnd2dTo3dGeometryGatesPassed"] = False
            self.write_evidence(evidence, sample_id, digest, payload)

            with self.assertRaisesRegex(ValueError, "geometryQualityGatePassed"):
                verifier.verify(splits, evidence, candidate)

    def test_geometry_evidence_from_different_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            candidate, digest = self.make_candidate(base)
            evidence = base / "evidence"
            wrong_digest = "f" * 64
            self.assertNotEqual(wrong_digest, digest)
            self.write_evidence(evidence, sample_id, wrong_digest)

            with self.assertRaisesRegex(ValueError, "modelSha256"):
                verifier.verify(splits, evidence, candidate)

    def test_private_source_field_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            candidate, digest = self.make_candidate(base)
            evidence = base / "evidence"
            payload = self.passing_evidence(sample_id, digest)
            payload["sourcePath"] = "/storage/emulated/0/client-villa.png"
            self.write_evidence(evidence, sample_id, digest, payload)

            with self.assertRaisesRegex(ValueError, "forbidden private source fields"):
                verifier.verify(splits, evidence, candidate)

    def test_unexpected_extra_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            candidate, digest = self.make_candidate(base)
            evidence = base / "evidence"
            self.write_evidence(evidence, sample_id, digest)
            extra = "sample-" + "4" * 32
            self.write_evidence(evidence, extra, digest)

            with self.assertRaisesRegex(RuntimeError, "does not exactly match"):
                verifier.verify(splits, evidence, candidate)


if __name__ == "__main__":
    unittest.main()
