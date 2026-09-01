from __future__ import annotations

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

    def passing_evidence(self, sample_id: str) -> dict:
        return {
            "schema": 1,
            "pipeline": "manzl-runtime-end-to-end-geometry-gates",
            "sampleId": sample_id,
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

    def write_evidence(self, root: pathlib.Path, sample_id: str, payload: dict | None = None) -> pathlib.Path:
        root.mkdir(parents=True, exist_ok=True)
        path = root / f"{sample_id}.geometry.json"
        path.write_text(json.dumps(payload or self.passing_evidence(sample_id)), encoding="utf-8")
        return path

    def test_exact_held_out_geometry_evidence_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            evidence = base / "evidence"
            self.write_evidence(evidence, sample_id)

            report = verifier.verify(splits, evidence)

            self.assertTrue(report["releaseGeometryEvidencePassed"])
            self.assertTrue(report["exactHeldOutSampleCoverage"])
            self.assertTrue(report["allEndToEnd2dTo3dGeometryGatesPassed"])
            self.assertFalse(report["runtimeThresholdsDuplicated"])
            self.assertFalse(report["releaseReady"])

    def test_missing_held_out_sample_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            self.make_splits(splits)
            evidence = base / "evidence"
            evidence.mkdir()

            with self.assertRaisesRegex(RuntimeError, "contains no samples"):
                verifier.verify(splits, evidence)

    def test_runtime_gate_failure_is_rejected_without_python_threshold_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            evidence = base / "evidence"
            payload = self.passing_evidence(sample_id)
            payload["geometryQualityGatePassed"] = False
            payload["endToEnd2dTo3dGeometryGatesPassed"] = False
            self.write_evidence(evidence, sample_id, payload)

            with self.assertRaisesRegex(ValueError, "geometryQualityGatePassed"):
                verifier.verify(splits, evidence)

    def test_private_source_field_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            evidence = base / "evidence"
            payload = self.passing_evidence(sample_id)
            payload["sourcePath"] = "/storage/emulated/0/client-villa.png"
            self.write_evidence(evidence, sample_id, payload)

            with self.assertRaisesRegex(ValueError, "forbidden private source fields"):
                verifier.verify(splits, evidence)

    def test_unexpected_extra_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            splits = base / "splits"
            splits.mkdir()
            sample_id = self.make_splits(splits)
            evidence = base / "evidence"
            self.write_evidence(evidence, sample_id)
            extra = "sample-" + "4" * 32
            self.write_evidence(evidence, extra)

            with self.assertRaisesRegex(RuntimeError, "does not exactly match"):
                verifier.verify(splits, evidence)


if __name__ == "__main__":
    unittest.main()
