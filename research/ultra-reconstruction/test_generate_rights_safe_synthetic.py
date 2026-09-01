from __future__ import annotations

import pathlib
import random
import sys
import unittest

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import generate_rights_safe_synthetic as generator  # noqa: E402


class RightsSafeSyntheticBoundsTest(unittest.TestCase):
    def test_fully_off_canvas_corner_is_ignored(self):
        raster = generator.Raster(64, random.Random(1))
        raster.draw_corner(generator.Point(-1000.0, -1000.0))
        self.assertEqual(float(raster.corners.max()), 0.0)

    def test_partially_visible_corner_is_clipped_and_drawn(self):
        raster = generator.Raster(64, random.Random(2))
        raster.draw_corner(generator.Point(-1.0, 12.0))
        self.assertGreater(float(raster.corners.max()), 0.0)
        self.assertEqual(raster.corners.shape, (64, 64))

    def test_fully_off_canvas_column_is_ignored(self):
        raster = generator.Raster(64, random.Random(3))
        before = raster.semantic.copy()
        raster.draw_column(generator.Point(1000.0, 1000.0), 14.0, 12.0, 0.3)
        np.testing.assert_array_equal(raster.semantic, before)

    def test_fully_off_canvas_wall_erase_is_ignored(self):
        raster = generator.Raster(64, random.Random(4))
        raster.semantic[:] = generator.CLASS["wall_face"]
        before = raster.semantic.copy()
        raster.erase_wall_segment(
            generator.Segment(
                generator.Point(-1000.0, -900.0),
                generator.Point(-800.0, -700.0),
                12.0,
            )
        )
        np.testing.assert_array_equal(raster.semantic, before)

    def test_training_workflow_seed_generates_full_batch_without_bounds_crash(self):
        rng = random.Random(43926)
        for _ in range(160):
            sample = generator.generate_sample(512, rng)
            self.assertEqual(sample["image"].shape, (512, 512, 3))
            self.assertEqual(sample["semantic"].shape, (512, 512))
            self.assertEqual(sample["corners"].shape, (512, 512))


if __name__ == "__main__":
    unittest.main()
