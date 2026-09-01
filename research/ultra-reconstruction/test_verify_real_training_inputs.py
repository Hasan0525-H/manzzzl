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

import verify_real_training_inputs as verifier  # noqa: E402


class VerifyRealTrainingInputsTest(unittest.TestCase):
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

    def make_fixture(self, root: pathlib.Path):
        groups = {
            "train": "private:" + "a" * 32,
            "validation": "private:" + "b" * 32,
            "test": "private:" + "c" * 32,
        }
        self.write_sample(root / "train", "1" * 32, groups["train"], 10)
        self.write_sample(root / "validation", "2" * 32, groups["validation"], 20)
        self.write_sample(root / "test", "3" * 32, groups["test"], 30)
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
        report_path = root / "materialization_report.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        return groups, report_path, report

    def test_valid_three_way_materialization_is_accepted_with_privacy_safe_fingerprints(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            self.make_fixture(root)
            report = verifier.verify(root)
            self.assertTrue(report["passed"])
            self.assertEqual(report["schema"], 2)
            self.assertEqual(report["trainerMayRead"], ["train", "validation"])
            self.assertEqual(report["trainerMustNotRead"], ["test"])
            self.assertTrue(report["testReservedForFinalEvaluation"])
            self.assertTrue(report["fingerprintContainsOnlyOpaqueSampleIds"])
            fingerprints = report["opaqueSplitSetFingerprints"]
            self.assertEqual(set(fingerprints), {"train", "validation", "test"})
            self.assertTrue(all(len(value) == 64 for value in fingerprints.values()))
            expected_test = verifier.opaque_set_fingerprint(sorted((root / "test").glob("*.npz")))
            self.assertEqual(fingerprints["test"], expected_test)

    def test_split_fingerprint_changes_when_opaque_membership_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            self.make_fixture(root)
            first = verifier.verify(root)["opaqueSplitSetFingerprints"]["test"]
            original = next((root / "test").glob("*.npz"))
            original.rename(root / "test" / ("sample-" + "4" * 32 + ".npz"))
            second = verifier.opaque_set_fingerprint(sorted((root / "test").glob("*.npz")))
            self.assertNotEqual(first, second)

    def test_missing_test_partition_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            self.make_fixture(root)
            for path in (root / "test").glob("*.npz"):
                path.unlink()
            with self.assertRaisesRegex(RuntimeError, "contains no samples: test"):
                verifier.verify(root)

    def test_nonopaque_filename_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            self.make_fixture(root)
            original = next((root / "train").glob("*.npz"))
            original.rename(root / "train" / "riyadh-client-villa.npz")
            with self.assertRaisesRegex(RuntimeError, "non-opaque filenames"):
                verifier.verify(root)

    def test_tampered_materialization_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            _, report_path, report = self.make_fixture(root)
            report["testReservedForFinalEvaluation"] = False
            report_path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "testReservedForFinalEvaluation"):
                verifier.verify(root)

    def test_family_leakage_between_validation_and_test_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "splits"
            root.mkdir()
            groups, _, _ = self.make_fixture(root)
            test_path = next((root / "test").glob("*.npz"))
            with np.load(test_path, allow_pickle=False) as sample:
                payload = {name: np.asarray(sample[name]) for name in sample.files}
            payload["source_group"] = np.asarray(groups["validation"])
            np.savez_compressed(test_path, **payload)
            with self.assertRaisesRegex(RuntimeError, "floor-plan family leakage"):
                verifier.verify(root)


if __name__ == "__main__":
    unittest.main()
