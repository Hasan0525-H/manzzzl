from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

import numpy as np


MODULE_PATH = pathlib.Path(__file__).with_name("verify_dataset_split.py")
spec = importlib.util.spec_from_file_location("verify_dataset_split", MODULE_PATH)
verify = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = verify
spec.loader.exec_module(verify)


class DatasetSplitGuardTest(unittest.TestCase):
    def write_sample(
        self,
        root: pathlib.Path,
        name: str,
        image: np.ndarray,
        source_group: str | None = None,
    ) -> pathlib.Path:
        root.mkdir(parents=True, exist_ok=True)
        path = root / name
        semantic = np.zeros(image.shape[:2], dtype=np.int64)
        payload = {"image": image, "semantic": semantic}
        if source_group is not None:
            payload["source_group"] = np.asarray(source_group)
        np.savez_compressed(path, **payload)
        return path

    def test_distinct_sources_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            self.write_sample(train, "a.npz", np.zeros((8, 9, 3), dtype=np.uint8), "family:a")
            image = np.zeros((8, 9, 3), dtype=np.uint8)
            image[2, 3] = [10, 20, 30]
            self.write_sample(validation, "b.npz", image, "family:b")

            report = verify.verify_split_independence(train, validation)

            self.assertEqual(1, report["trainSamples"])
            self.assertEqual(1, report["validationSamples"])
            self.assertEqual(0, report["crossSplitExactOverlap"])
            self.assertEqual(0, report["crossSplitGroupOverlap"])
            self.assertFalse(report["exactSourceLeakage"])
            self.assertFalse(report["floorPlanFamilyLeakage"])

    def test_same_source_with_different_name_and_dtype_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            image = np.arange(8 * 9 * 3, dtype=np.uint8).reshape(8, 9, 3)
            self.write_sample(train, "original-plan.npz", image)
            self.write_sample(validation, "renamed-copy.npz", image.astype(np.float32) / 255.0)

            with self.assertRaisesRegex(RuntimeError, "held-out dataset leakage"):
                verify.verify_split_independence(train, validation)

    def test_different_rasters_from_same_floor_plan_family_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            first = np.zeros((10, 11, 3), dtype=np.uint8)
            first[1:4, 1:8] = 40
            second = np.full((10, 11, 3), 250, dtype=np.uint8)
            second[3:8, 2:9] = 120
            self.write_sample(train, "scan.npz", first, "private-plan:8f7c")
            self.write_sample(validation, "cad-export.npz", second, "private-plan:8f7c")

            with self.assertRaisesRegex(RuntimeError, "floor-plan family leakage"):
                verify.verify_split_independence(train, validation)

    def test_multiple_variants_of_one_family_are_allowed_inside_same_split(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            first = np.zeros((7, 8, 3), dtype=np.uint8)
            first[1, 1] = [20, 20, 20]
            second = np.zeros((7, 8, 3), dtype=np.uint8)
            second[5, 6] = [30, 30, 30]
            held_out = np.zeros((7, 8, 3), dtype=np.uint8)
            held_out[3, 4] = [80, 80, 80]
            self.write_sample(train, "variant-a.npz", first, "family:train-1")
            self.write_sample(train, "variant-b.npz", second, "family:train-1")
            self.write_sample(validation, "held-out.npz", held_out, "family:val-9")

            report = verify.verify_split_independence(train, validation)

            self.assertEqual(1, report["trainSourceGroups"])
            self.assertEqual(1, report["validationSourceGroups"])
            self.assertEqual(0, report["crossSplitGroupOverlap"])

    def test_duplicate_source_inside_one_split_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            image = np.full((6, 7, 3), 128, dtype=np.uint8)
            self.write_sample(train, "first.npz", image)
            self.write_sample(train, "second.npz", image.copy())
            validation_image = image.copy()
            validation_image[0, 0] = [0, 0, 0]
            self.write_sample(validation, "held-out.npz", validation_image)

            with self.assertRaisesRegex(RuntimeError, "duplicate source rasters"):
                verify.verify_split_independence(train, validation)

    def test_empty_or_invalid_source_group_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            self.write_sample(train, "bad.npz", np.zeros((5, 5, 3), dtype=np.uint8), "   ")
            image = np.ones((5, 5, 3), dtype=np.uint8)
            self.write_sample(validation, "good.npz", image, "family:good")

            with self.assertRaisesRegex(ValueError, "must not be empty"):
                verify.verify_split_independence(train, validation)

    def test_same_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary) / "same"
            self.write_sample(root, "one.npz", np.zeros((4, 4, 3), dtype=np.uint8))

            with self.assertRaisesRegex(RuntimeError, "same directory"):
                verify.verify_split_independence(root, root)


if __name__ == "__main__":
    unittest.main()
