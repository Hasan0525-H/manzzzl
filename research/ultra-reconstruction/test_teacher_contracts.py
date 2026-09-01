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


if __name__ == "__main__":
    unittest.main()
