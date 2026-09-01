import pathlib
import tempfile
import unittest

import numpy as np
from shapely.geometry import LineString, Polygon

import render_resplan_training as resplan


def sample_plan(plan_id: str = "contract-1") -> dict:
    outer = [(-5.0, -4.0), (5.0, -4.0), (5.0, 4.0), (-5.0, 4.0), (-5.0, -4.0)]
    inner_hole = [(-4.6, -3.6), (-4.6, 3.6), (4.6, 3.6), (4.6, -3.6), (-4.6, -3.6)]
    wall = Polygon(shell=outer, holes=[inner_hole])
    inner = Polygon([(-4.6, -3.6), (4.6, -3.6), (4.6, 3.6), (-4.6, 3.6)])
    return {
        "id": plan_id,
        "inner": inner,
        "wall": wall,
        "door": LineString([(-0.55, -3.6), (0.55, -3.6)]),
        "window": LineString([(4.6, -0.8), (4.6, 0.8)]),
        "stair": Polygon([(-1.0, -0.7), (1.0, -0.7), (1.0, 0.7), (-1.0, 0.7)]),
        "living": inner,
    }


class ResPlanAdapterTest(unittest.TestCase):
    def test_vector_geometry_becomes_dense_student_supervision(self):
        payload = resplan.render(sample_plan(), size=256, seed=41)
        self.assertIsNotNone(payload)
        payload = payload or {}
        self.assertEqual(payload["image"].shape, (256, 256, 3))
        self.assertEqual(payload["semantic"].shape, (256, 256))
        classes = set(np.unique(payload["semantic"]).tolist())
        self.assertIn(resplan.WALL, classes)
        self.assertIn(resplan.DOOR, classes)
        self.assertIn(resplan.WINDOW, classes)
        self.assertIn(resplan.STAIR, classes)
        self.assertTrue(np.all(payload["corner_mask"] == 0.0))
        self.assertTrue(np.all(payload["orientation_mask"] == 0.0))
        self.assertGreater(int(payload["wall_mask"].sum()), 200)

    def test_written_sample_preserves_source_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = pathlib.Path(temporary)
            written = resplan.write_split([sample_plan("house/42")], output, count=1, size=256, seed=77)
            self.assertEqual(1, written)
            files = list(output.glob("*.npz"))
            self.assertEqual(1, len(files))
            with np.load(files[0], allow_pickle=False) as payload:
                self.assertEqual("resplan:house_42", np.asarray(payload["source_group"]).item())

    def test_empty_or_degenerate_plan_is_rejected(self):
        self.assertIsNone(resplan.render({}, size=256, seed=1))
        degenerate = {"inner": Polygon([(0, 0), (1, 0), (2, 0)]), "wall": None}
        self.assertIsNone(resplan.render(degenerate, size=256, seed=2))


if __name__ == "__main__":
    unittest.main()
