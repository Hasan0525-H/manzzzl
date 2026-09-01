#!/usr/bin/env python3
"""Regression tests for the pinned Raster2Graph-512 Raster2Seq semantic contract."""

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


class Raster2Graph512ContractTest(unittest.TestCase):
    def test_categories_9_and_10_remain_room_boundaries_not_openings(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-r2g512-contract-") as raw_tmp:
            root = pathlib.Path(raw_tmp)
            predictions = root / "predictions"
            json_root = predictions / "jsons"
            output = root / "adapted"
            json_root.mkdir(parents=True)

            image = np.full((64, 64, 3), 255, dtype=np.uint8)
            self.assertTrue(cv2.imwrite(str(predictions / "sample.png"), image))

            # Official R2G: 1=living_room, 9=washing_room, 10=PS. None is an opening.
            records = [
                {"category_id": 1, "segmentation": [4, 4, 28, 4, 28, 28, 4, 28]},
                {"category_id": 9, "segmentation": [34, 4, 58, 4, 58, 28, 34, 28]},
                {"category_id": 10, "segmentation": [4, 34, 28, 34, 28, 58, 4, 58]},
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
                classes = [str(value) for value in sample["semantic_classes"].tolist()]
                valid = np.asarray(sample["valid_mask"], dtype=np.uint8)
                fmt = str(np.asarray(sample["teacher_format"]).reshape(-1)[0])

                self.assertEqual(classes, ["room_boundary"])
                self.assertNotIn("door", classes)
                self.assertNotIn("window", classes)
                self.assertEqual(fmt, "raster2seq-r2g512-json-v3-room-boundary-only")

                # All three R2G polygons, including ids 9/10, own boundary evidence.
                self.assertEqual(int(valid[4, 12]), 1)
                self.assertEqual(int(valid[4, 42]), 1)
                self.assertEqual(int(valid[34, 12]), 1)
                # Interiors are abstentions.
                self.assertEqual(int(valid[16, 16]), 0)
                self.assertEqual(int(valid[16, 46]), 0)
                self.assertEqual(int(valid[46, 16]), 0)

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
            self.assertEqual(known_names, {"room_boundary"})
            self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX["door"]])
            self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX["window"]])

    def test_unknown_r2g_category_fails_closed_when_it_is_the_only_record(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-r2g512-unknown-") as raw_tmp:
            root = pathlib.Path(raw_tmp)
            predictions = root / "predictions"
            json_root = predictions / "jsons"
            output = root / "adapted"
            json_root.mkdir(parents=True)
            image = np.full((32, 32, 3), 255, dtype=np.uint8)
            self.assertTrue(cv2.imwrite(str(predictions / "sample.png"), image))
            json_path = json_root / "sample.json"
            json_path.write_text(
                json.dumps([{"category_id": 99, "segmentation": [2, 2, 28, 2, 28, 28, 2, 28]}]),
                encoding="utf-8",
            )

            with self.assertRaises(RuntimeError):
                raster2seq.adapt_one(
                    json_path=json_path,
                    save_root=predictions,
                    output_root=output,
                    confidence=0.90,
                    line_width=2,
                )


if __name__ == "__main__":
    unittest.main()
