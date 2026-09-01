import unittest

import numpy as np

import evaluate_student_onnx as evaluation


class StudentMetricTest(unittest.TestCase):
    def test_perfect_segmentation_has_unit_iou(self):
        target = np.array([[0, 1, 1], [2, 2, 0]], dtype=np.int64)
        valid = np.ones_like(target, dtype=bool)
        metrics = evaluation.segmentation_metrics(target.copy(), target, valid, classes=3)
        self.assertAlmostEqual(metrics["mean_iou"], 1.0, places=6)
        np.testing.assert_allclose(metrics["iou"], np.ones(3), atol=1e-9)

    def test_invalid_pixels_do_not_penalize_prediction(self):
        target = np.array([[0, 1], [1, 0]], dtype=np.int64)
        predicted = np.array([[0, 1], [0, 1]], dtype=np.int64)
        valid = np.array([[True, True], [False, False]])
        metrics = evaluation.segmentation_metrics(predicted, target, valid, classes=2)
        self.assertAlmostEqual(metrics["mean_iou"], 1.0, places=6)

    def test_false_positive_reduces_class_iou(self):
        target = np.array([[0, 1], [0, 0]], dtype=np.int64)
        predicted = np.array([[0, 1], [1, 0]], dtype=np.int64)
        valid = np.ones_like(target, dtype=bool)
        metrics = evaluation.segmentation_metrics(predicted, target, valid, classes=2)
        self.assertAlmostEqual(float(metrics["iou"][1]), 0.5, places=6)
        self.assertLess(metrics["mean_iou"], 1.0)


if __name__ == "__main__":
    unittest.main()
