import pathlib
import tempfile
import unittest

import numpy as np

import generate_synthetic_corpus as synthetic


class SyntheticCorpusTest(unittest.TestCase):
    def test_forced_sample_has_dense_exact_training_contract(self):
        sample = synthetic.generate_sample(
            size=256,
            rng=np.random.default_rng(20260901),
            force_features=True,
        )
        payload = sample.as_npz()

        self.assertEqual(payload["image"].shape, (256, 256, 3))
        self.assertEqual(payload["semantic"].shape, (256, 256))
        self.assertEqual(payload["orientation"].shape, (2, 256, 256))
        self.assertEqual(payload["image"].dtype, np.uint8)
        self.assertGreater(int(payload["wall_mask"].sum()), 250)
        self.assertGreaterEqual(int(payload["semantic"].min()), 0)
        self.assertLess(int(payload["semantic"].max()), synthetic.CLASS_COUNT)

        classes = set(np.unique(payload["semantic"]).tolist())
        self.assertIn(synthetic.WALL, classes)
        self.assertIn(synthetic.STAIR, classes)
        self.assertIn(synthetic.COLUMN, classes)
        self.assertIn(synthetic.COURTYARD, classes)
        self.assertIn(synthetic.SHAFT, classes)
        self.assertTrue(synthetic.DOOR in classes or synthetic.WINDOW in classes)

        wall = payload["wall_mask"] > 0.5
        norms = np.sqrt(payload["orientation"][0] ** 2 + payload["orientation"][1] ** 2)
        self.assertTrue(np.isfinite(norms).all())
        self.assertGreater(float(np.mean(norms[wall] > 0.98)), 0.98)
        self.assertTrue(np.all(payload["supervision_mask"] == 1.0))

    def test_saved_npz_loads_without_pickle_and_matches_student_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            synthetic.generate_corpus(root, count=2, size=192, seed=77)
            files = sorted(root.glob("*.npz"))
            self.assertEqual(len(files), 2)
            with np.load(files[0], allow_pickle=False) as sample:
                required = {
                    "image",
                    "semantic",
                    "semantic_confidence",
                    "supervision_mask",
                    "corners",
                    "corner_mask",
                    "orientation",
                    "wall_mask",
                    "orientation_mask",
                }
                self.assertTrue(required.issubset(set(sample.files)))
                self.assertEqual(sample["image"].shape[:2], sample["semantic"].shape)
                self.assertEqual(sample["orientation"].shape[1:], sample["semantic"].shape)


if __name__ == "__main__":
    unittest.main()
