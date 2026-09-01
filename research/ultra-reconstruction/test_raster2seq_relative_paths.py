from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import adapt_raster2seq_predictions as adapter  # noqa: E402
import run_raster2seq_teacher as runner  # noqa: E402


class Raster2SeqRelativePathTest(unittest.TestCase):
    def record(self):
        return {
            "category_id": 1,
            "segmentation": [[4.0, 4.0], [26.0, 4.0], [26.0, 26.0], [4.0, 26.0]],
            "id": 0,
        }

    def test_duplicate_basenames_in_different_families_never_collide(self):
        with tempfile.TemporaryDirectory(prefix="manzl-r2s-paths-") as tmp:
            root = pathlib.Path(tmp)
            predictions = root / "predictions"
            adapted = root / "adapted"
            image_a = np.full((32, 32, 3), 40, dtype=np.uint8)
            image_b = np.full((32, 32, 3), 210, dtype=np.uint8)

            runner.write_prediction(
                predictions,
                pathlib.Path("villaA/plan.jpg"),
                image_a,
                [self.record()],
            )
            runner.write_prediction(
                predictions,
                pathlib.Path("villaB/plan.png"),
                image_b,
                [self.record()],
            )

            json_a = predictions / "jsons" / "villaA" / "plan.json"
            json_b = predictions / "jsons" / "villaB" / "plan.json"
            image_path_a = predictions / "villaA" / "plan.png"
            image_path_b = predictions / "villaB" / "plan.png"
            for path in (json_a, json_b, image_path_a, image_path_b):
                self.assertTrue(path.is_file(), path)

            self.assertEqual(json.loads(json_a.read_text(encoding="utf-8"))[0]["image_id"], "villaA/plan")
            self.assertEqual(json.loads(json_b.read_text(encoding="utf-8"))[0]["image_id"], "villaB/plan")

            with mock.patch.object(
                sys,
                "argv",
                [
                    "adapt_raster2seq_predictions.py",
                    "--prediction-root",
                    str(predictions),
                    "--output",
                    str(adapted),
                    "--line-width",
                    "2",
                ],
            ):
                self.assertEqual(adapter.main(), 0)

            output_a = adapted / "villaA" / "plan.npz"
            output_b = adapted / "villaB" / "plan.npz"
            self.assertTrue(output_a.is_file())
            self.assertTrue(output_b.is_file())
            self.assertNotEqual(output_a, output_b)

            with np.load(output_a, allow_pickle=False) as a, np.load(output_b, allow_pickle=False) as b:
                self.assertLess(float(np.asarray(a["image"]).mean()), 80.0)
                self.assertGreater(float(np.asarray(b["image"]).mean()), 170.0)
                self.assertEqual(
                    str(np.asarray(a["teacher_format"]).reshape(-1)[0]),
                    "raster2seq-r2g512-json-v3-room-boundary-only",
                )

    def test_adapter_rejects_json_outside_prediction_json_root(self):
        with tempfile.TemporaryDirectory(prefix="manzl-r2s-escape-") as tmp:
            root = pathlib.Path(tmp)
            predictions = root / "predictions"
            (predictions / "jsons").mkdir(parents=True)
            outside = root / "outside.json"
            outside.write_text("[]", encoding="utf-8")
            with self.assertRaises(ValueError):
                adapter.adapt_one(outside, predictions, root / "out", 0.9, 2)

    def test_runner_rejects_parent_traversal_output_identity(self):
        with tempfile.TemporaryDirectory(prefix="manzl-r2s-traversal-") as tmp:
            root = pathlib.Path(tmp)
            with self.assertRaisesRegex(ValueError, "safe and relative"):
                runner.write_prediction(
                    root,
                    pathlib.Path("../plan.png"),
                    np.zeros((16, 16, 3), dtype=np.uint8),
                    [],
                )


if __name__ == "__main__":
    unittest.main()
