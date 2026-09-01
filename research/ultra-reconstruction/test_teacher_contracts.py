#!/usr/bin/env python3
"""Contract tests for Manzl heavy-teacher adapters and consensus ingestion."""

from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

import cv2
import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import adapt_raster2seq_predictions as raster2seq  # noqa: E402
import build_teacher_consensus as consensus  # noqa: E402
import run_mitunet_teacher as mitunet  # noqa: E402


class Raster2SeqAdapterContractTest(unittest.TestCase):
    def test_unknown_semantic_classes_are_true_abstentions(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-r2s-contract-") as raw_tmp:
            root = pathlib.Path(raw_tmp)
            predictions = root / "predictions"
            json_root = predictions / "jsons"
            output = root / "adapted"
            json_root.mkdir(parents=True)

            image = np.full((48, 48, 3), 255, dtype=np.uint8)
            self.assertTrue(cv2.imwrite(str(predictions / "sample.png"), image))

            # One room polygon plus explicit door/window polygons. Category ids follow the pinned
            # Raster2Seq CubiCasa-style saved-prediction contract.
            records = [
                {
                    "category_id": 1,
                    "segmentation": [6, 6, 41, 6, 41, 41, 6, 41],
                },
                {
                    "category_id": raster2seq.CC5K_DOOR,
                    "segmentation": [18, 5, 25, 5, 25, 8, 18, 8],
                },
                {
                    "category_id": raster2seq.CC5K_WINDOW,
                    "segmentation": [40, 18, 43, 18, 43, 26, 40, 26],
                },
            ]
            json_path = json_root / "sample.json"
            json_path.write_text(json.dumps(records), encoding="utf-8")

            destination = raster2seq.adapt_one(
                json_path=json_path,
                save_root=predictions,
                output_root=output,
                confidence=0.90,
                line_width=2,
            )

            with np.load(destination, allow_pickle=False) as sample:
                exported_classes = [str(value) for value in sample["semantic_classes"].tolist()]
                self.assertEqual(exported_classes, raster2seq.EVIDENCE_CLASSES)
                self.assertNotIn("background", exported_classes)
                self.assertNotIn("wall_face", exported_classes)
                self.assertNotIn("stair", exported_classes)
                self.assertNotIn("column", exported_classes)
                self.assertNotIn("courtyard", exported_classes)
                self.assertNotIn("shaft", exported_classes)

                valid = np.asarray(sample["valid_mask"], dtype=np.uint8)
                semantic = np.asarray(sample["semantic"], dtype=np.int64)
                self.assertEqual(int(valid[24, 24]), 0, "room interior must be an abstention")
                self.assertGreater(int(valid.sum()), 0)
                self.assertLess(int(valid.sum()), valid.size // 2)
                self.assertTrue(np.all(semantic[valid > 0] < len(raster2seq.EVIDENCE_CLASSES)))

            loaded = consensus.load_prediction(
                consensus.TeacherSpec("raster2seq", output, 1.0),
                pathlib.Path("sample.npz"),
            )
            self.assertIsNotNone(loaded)
            assert loaded is not None

            known_names = {
                consensus.SEMANTIC_CLASSES[index]
                for index in np.flatnonzero(loaded.class_known)
            }
            self.assertEqual(known_names, set(raster2seq.EVIDENCE_CLASSES))
            self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX["wall_face"]])
            self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX["background"]])
            self.assertFalse(bool(loaded.valid[24, 24]))


class MitUNetTeacherContractTest(unittest.TestCase):
    def test_wall_teacher_only_claims_binary_wall_classes(self) -> None:
        wall_probability = np.asarray(
            [
                [0.02, 0.10, 0.88, 0.97],
                [0.05, 0.49, 0.51, 0.92],
                [0.08, 0.25, 0.75, 0.95],
                [0.01, 0.15, 0.85, 0.99],
            ],
            dtype=np.float32,
        )
        probabilities, confidence, valid = mitunet.encode_wall_probability(wall_probability)
        self.assertEqual(probabilities.shape, (2, 4, 4))
        np.testing.assert_allclose(probabilities.sum(axis=0), 1.0, atol=1e-6)
        self.assertEqual(confidence.shape, wall_probability.shape)
        self.assertTrue(np.all((confidence >= 0.0) & (confidence <= 1.0)))
        self.assertTrue(np.all(valid == 1))
        self.assertLess(float(confidence[1, 1]), float(confidence[0, 0]))

        with tempfile.TemporaryDirectory(prefix="manzl-mitunet-contract-") as raw_tmp:
            root = pathlib.Path(raw_tmp)
            output = root / "mitunet"
            destination = output / "sample.npz"
            image = np.full((4, 4, 3), 127, dtype=np.uint8)
            mitunet.save_prediction(destination, image, wall_probability)

            loaded = consensus.load_prediction(
                consensus.TeacherSpec("mitunet", output, 1.0),
                pathlib.Path("sample.npz"),
            )
            self.assertIsNotNone(loaded)
            assert loaded is not None

            known_names = {
                consensus.SEMANTIC_CLASSES[index]
                for index in np.flatnonzero(loaded.class_known)
            }
            self.assertEqual(known_names, {"background", "wall_face"})
            for class_name in ("door", "window", "stair", "column", "room_boundary", "courtyard", "shaft"):
                self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX[class_name]])


if __name__ == "__main__":
    unittest.main()
