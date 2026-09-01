#!/usr/bin/env python3
"""Regression tests for the strict three-teacher real-plan consensus bridge."""

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

import build_real_teacher_consensus as real_consensus  # noqa: E402


class RealTeacherAlignmentTest(unittest.TestCase):
    def _write_teacher_sample(
        self,
        root: pathlib.Path,
        relative: pathlib.Path,
        image: np.ndarray,
    ) -> None:
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        semantic = np.zeros(image.shape[:2], dtype=np.int64)
        np.savez_compressed(
            destination,
            image=image,
            semantic=semantic,
            semantic_classes=np.asarray(["background"], dtype="U32"),
        )

    def test_exact_same_sample_sets_and_rasters_are_accepted(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-real-consensus-") as raw_tmp:
            base = pathlib.Path(raw_tmp)
            image = np.arange(8 * 8 * 3, dtype=np.uint8).reshape(8, 8, 3)
            teachers = []
            for teacher_id in real_consensus.TEACHER_ORDER:
                root = base / teacher_id
                self._write_teacher_sample(root, pathlib.Path("nested/sample.npz"), image)
                teachers.append(real_consensus.TeacherRoot(teacher_id, root))

            samples = real_consensus.validate_exact_alignment(teachers)
            self.assertEqual(samples, [pathlib.Path("nested/sample.npz")])

    def test_same_shape_but_different_source_raster_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-real-consensus-misaligned-") as raw_tmp:
            base = pathlib.Path(raw_tmp)
            image = np.zeros((8, 8, 3), dtype=np.uint8)
            teachers = []
            for teacher_id in real_consensus.TEACHER_ORDER:
                root = base / teacher_id
                current = image.copy()
                if teacher_id == "cubicasa":
                    current[3, 4, 1] = 255
                self._write_teacher_sample(root, pathlib.Path("sample.npz"), current)
                teachers.append(real_consensus.TeacherRoot(teacher_id, root))

            with self.assertRaisesRegex(RuntimeError, "not pixel-aligned"):
                real_consensus.validate_exact_alignment(teachers)

    def test_missing_teacher_sample_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-real-consensus-missing-") as raw_tmp:
            base = pathlib.Path(raw_tmp)
            image = np.zeros((8, 8, 3), dtype=np.uint8)
            teachers = []
            for teacher_id in real_consensus.TEACHER_ORDER:
                root = base / teacher_id
                self._write_teacher_sample(root, pathlib.Path("sample.npz"), image)
                if teacher_id != "mitunet":
                    self._write_teacher_sample(root, pathlib.Path("second.npz"), image)
                teachers.append(real_consensus.TeacherRoot(teacher_id, root))

            with self.assertRaisesRegex(RuntimeError, "sample sets are not identical"):
                real_consensus.validate_exact_alignment(teachers)

    def test_source_group_manifest_must_cover_exact_sample_set(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-source-groups-") as raw_tmp:
            base = pathlib.Path(raw_tmp)
            manifest = base / "groups.json"
            manifest.write_text(
                json.dumps({"schema": 1, "groups": {"nested/a.npz": "house:A"}}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "exact teacher sample set"):
                real_consensus.load_source_groups(
                    manifest,
                    [pathlib.Path("nested/a.npz"), pathlib.Path("nested/b.npz")],
                )

    def test_source_group_manifest_preserves_family_variants(self) -> None:
        with tempfile.TemporaryDirectory(prefix="manzl-source-groups-valid-") as raw_tmp:
            base = pathlib.Path(raw_tmp)
            manifest = base / "groups.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "groups": {
                            "scan/a.npz": "private:house-17",
                            "cad/a.npz": "private:house-17",
                            "scan/b.npz": "private:house-99",
                        },
                    }
                ),
                encoding="utf-8",
            )
            samples = [
                pathlib.Path("cad/a.npz"),
                pathlib.Path("scan/a.npz"),
                pathlib.Path("scan/b.npz"),
            ]

            groups = real_consensus.load_source_groups(manifest, samples)

            self.assertEqual("private:house-17", groups[pathlib.Path("scan/a.npz")])
            self.assertEqual("private:house-17", groups[pathlib.Path("cad/a.npz")])
            self.assertEqual(2, len(set(groups.values())))


if __name__ == "__main__":
    unittest.main()
