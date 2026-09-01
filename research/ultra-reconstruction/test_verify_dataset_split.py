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
    def write_sample(self, root: pathlib.Path, name: str, image: np.ndarray) -> pathlib.Path:
        root.mkdir(parents=True, exist_ok=True)
        path = root / name
        semantic = np.zeros(image.shape[:2], dtype=np.int64)
        np.savez_compressed(path, image=image, semantic=semantic)
        return path

    def test_distinct_sources_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            train = root / "train"
            validation = root / "validation"
            self.write_sample(train, "a.npz", np.zeros((8, 9, 3), dtype=np.uint8))
            image = np.zeros((8, 9, 3), dtype=np.uint8)
            image[2, 3] = [10, 20, 30]
            self.write_sample(validation, "b.npz", image)

            report = verify.verify_split_independence(train, validation)

            self.assertEqual(1, report["trainSamples"])
            self.assertEqual(1, report["validationSamples"])
            self.assertEqual(0, report["crossSplitOverlap"])
            self.assertFalse(report["exactSourceLeakage"])

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

    def test_same_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary) / "same"
            self.write_sample(root, "one.npz", np.zeros((4, 4, 3), dtype=np.uint8))

            with self.assertRaisesRegex(RuntimeError, "same directory"):
                verify.verify_split_independence(root, root)


if __name__ == "__main__":
    unittest.main()
