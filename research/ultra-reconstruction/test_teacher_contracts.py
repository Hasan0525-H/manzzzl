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
import run_cubicasa_teacher as cubicasa  # noqa: E402
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


class CubiCasaTeacherContractTest(unittest.TestCase):
    def test_floortrans_mapping_preserves_wall_and_opening_votes(self) -> None:
        height = width = 4
        rooms = np.zeros((cubicasa.ROOM_CHANNELS, height, width), dtype=np.float32)
        icons = np.zeros((cubicasa.ICON_CHANNELS, height, width), dtype=np.float32)

        # Default to confident background/empty evidence everywhere.
        rooms[0] = 0.95
        rooms[cubicasa.ROOM_WALL] = 0.05
        icons[0] = 0.98
        icons[cubicasa.ICON_WINDOW] = 0.01
        icons[cubicasa.ICON_DOOR] = 0.01

        # Wall-only pixel. With no room interior on either side it must stay a wall, not a boundary.
        rooms[0, 0, 1] = 0.02
        rooms[cubicasa.ROOM_WALL, 0, 1] = 0.98

        # Door/window deliberately sit on strong wall evidence. The semantic mapper must keep the
        # opening as the winning opinion instead of letting the host wall suppress it.
        rooms[0, 1, 1] = 0.02
        rooms[cubicasa.ROOM_WALL, 1, 1] = 0.98
        icons[0, 1, 1] = 0.01
        icons[cubicasa.ICON_DOOR, 1, 1] = 0.98
        icons[cubicasa.ICON_WINDOW, 1, 1] = 0.01

        rooms[0, 2, 2] = 0.02
        rooms[cubicasa.ROOM_WALL, 2, 2] = 0.98
        icons[0, 2, 2] = 0.01
        icons[cubicasa.ICON_WINDOW, 2, 2] = 0.98
        icons[cubicasa.ICON_DOOR, 2, 2] = 0.01

        probabilities, confidence, valid = cubicasa.encode_floortrans_probabilities(rooms, icons)
        self.assertEqual(probabilities.shape, (len(cubicasa.LOCAL_CLASSES), height, width))
        np.testing.assert_allclose(probabilities.sum(axis=0), 1.0, atol=1e-6)
        self.assertTrue(np.all((confidence >= 0.0) & (confidence <= 1.0)))
        self.assertTrue(np.all(valid == 1))

        winners = np.argmax(probabilities, axis=0)
        self.assertEqual(int(winners[0, 0]), cubicasa.LOCAL_CLASS["background"])
        self.assertEqual(int(winners[0, 1]), cubicasa.LOCAL_CLASS["wall_face"])
        self.assertEqual(int(winners[1, 1]), cubicasa.LOCAL_CLASS["door"])
        self.assertEqual(int(winners[2, 2]), cubicasa.LOCAL_CLASS["window"])

        with tempfile.TemporaryDirectory(prefix="manzl-cubicasa-contract-") as raw_tmp:
            root = pathlib.Path(raw_tmp)
            output = root / "cubicasa"
            destination = output / "sample.npz"
            image = np.full((height, width, 3), 200, dtype=np.uint8)
            cubicasa.save_prediction(destination, image, rooms, icons)

            loaded = consensus.load_prediction(
                consensus.TeacherSpec("cubicasa", output, 1.0),
                pathlib.Path("sample.npz"),
            )
            self.assertIsNotNone(loaded)
            assert loaded is not None
            known_names = {
                consensus.SEMANTIC_CLASSES[index]
                for index in np.flatnonzero(loaded.class_known)
            }
            self.assertEqual(
                known_names,
                {"background", "wall_face", "door", "window", "room_boundary"},
            )
            for class_name in ("stair", "column", "courtyard", "shaft"):
                self.assertFalse(loaded.class_known[consensus.CLASS_TO_INDEX[class_name]])

    def test_room_segmentation_transition_exports_boundary_vote(self) -> None:
        height = width = 9
        rooms = np.zeros((cubicasa.ROOM_CHANNELS, height, width), dtype=np.float32)
        icons = np.zeros((cubicasa.ICON_CHANNELS, height, width), dtype=np.float32)
        rooms[cubicasa.ROOM_BACKGROUND] = 0.99
        icons[0] = 1.0

        room_class = 11  # CubiCasa generic Room class.
        rooms[cubicasa.ROOM_BACKGROUND, 2:7, 2:5] = 0.01
        rooms[room_class, 2:7, 2:5] = 0.99
        rooms[cubicasa.ROOM_BACKGROUND, 2:7, 5] = 0.01
        rooms[cubicasa.ROOM_WALL, 2:7, 5] = 0.99

        boundary = cubicasa.derive_room_boundary_probability(rooms)
        self.assertGreater(float(boundary[4, 4]), 0.95)
        self.assertGreater(float(boundary[4, 5]), 0.95)
        self.assertEqual(float(boundary[4, 2]), 0.0)

        probabilities, _, _ = cubicasa.encode_floortrans_probabilities(rooms, icons)
        winners = np.argmax(probabilities, axis=0)
        self.assertEqual(int(winners[4, 4]), cubicasa.LOCAL_CLASS["room_boundary"])
        self.assertEqual(int(winners[4, 5]), cubicasa.LOCAL_CLASS["room_boundary"])
        self.assertEqual(int(winners[4, 2]), cubicasa.LOCAL_CLASS["background"])


class ConsensusQuorumContractTest(unittest.TestCase):
    @staticmethod
    def prediction(
        teacher_id: str,
        known_names: set[str],
        probabilities_by_name: dict[str, float],
    ) -> consensus.TeacherPrediction:
        class_count = len(consensus.SEMANTIC_CLASSES)
        probs = np.zeros((class_count, 1, 1), dtype=np.float32)
        known = np.zeros(class_count, dtype=bool)
        for name in known_names:
            known[consensus.CLASS_TO_INDEX[name]] = True
        for name, probability in probabilities_by_name.items():
            probs[consensus.CLASS_TO_INDEX[name], 0, 0] = probability
        return consensus.TeacherPrediction(
            teacher_id=teacher_id,
            weight=1.0,
            probs=probs,
            class_known=known,
            confidence=np.ones((1, 1), dtype=np.float32),
            valid=np.ones((1, 1), dtype=bool),
            image=None,
            corners=None,
            corner_confidence=None,
            orientation=None,
        )

    def test_single_high_probability_class_cannot_veto_two_teacher_quorum(self) -> None:
        wall_index = consensus.CLASS_TO_INDEX["wall_face"]
        background_index = consensus.CLASS_TO_INDEX["background"]
        room_boundary_index = consensus.CLASS_TO_INDEX["room_boundary"]

        predictions = [
            self.prediction(
                "mitunet",
                {"background", "wall_face"},
                {"background": 0.10, "wall_face": 0.90},
            ),
            self.prediction(
                "cubicasa",
                {"background", "wall_face", "door", "window", "room_boundary"},
                {"background": 0.12, "wall_face": 0.88, "door": 0.0, "window": 0.0, "room_boundary": 0.0},
            ),
            self.prediction(
                "raster2seq",
                {"door", "window", "room_boundary"},
                {"door": 0.0, "window": 0.0, "room_boundary": 1.0},
            ),
        ]

        semantic, probability, supervision, votes = consensus.semantic_consensus(
            predictions=predictions,
            min_votes=2,
            critical_min_votes=2,
            min_probability=0.72,
            min_margin=0.18,
        )

        # Raster2Seq has a numerically higher single vote for room_boundary, but it has no quorum.
        # The two independent wall teachers do have quorum and must remain trainable supervision.
        self.assertEqual(int(semantic[0, 0]), wall_index)
        self.assertEqual(float(supervision[0, 0]), 1.0)
        self.assertEqual(int(votes[0, 0]), 2)
        self.assertGreaterEqual(float(probability[0, 0]), 0.88)
        self.assertNotEqual(int(semantic[0, 0]), room_boundary_index)
        self.assertNotEqual(int(semantic[0, 0]), background_index)

    def test_raster2seq_and_cubicasa_form_independent_room_boundary_quorum(self) -> None:
        room_boundary_index = consensus.CLASS_TO_INDEX["room_boundary"]
        predictions = [
            self.prediction(
                "mitunet",
                {"background", "wall_face"},
                {"background": 0.05, "wall_face": 0.95},
            ),
            self.prediction(
                "cubicasa",
                {"background", "wall_face", "door", "window", "room_boundary"},
                {"background": 0.02, "wall_face": 0.03, "door": 0.0, "window": 0.0, "room_boundary": 0.95},
            ),
            self.prediction(
                "raster2seq",
                {"door", "window", "room_boundary"},
                {"door": 0.0, "window": 0.0, "room_boundary": 0.92},
            ),
        ]

        semantic, probability, supervision, votes = consensus.semantic_consensus(
            predictions=predictions,
            min_votes=2,
            critical_min_votes=2,
            min_probability=0.72,
            min_margin=0.18,
        )

        self.assertEqual(int(semantic[0, 0]), room_boundary_index)
        self.assertEqual(float(supervision[0, 0]), 1.0)
        self.assertEqual(int(votes[0, 0]), 2)
        self.assertGreater(float(probability[0, 0]), 0.90)


if __name__ == "__main__":
    unittest.main()
