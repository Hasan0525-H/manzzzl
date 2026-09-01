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

    def test_corner_metrics_use_android_runtime_snap_threshold(self):
        probabilities = np.array([[0.90, 0.57], [0.55, 0.10]], dtype=np.float32)
        target = np.array([[1.0, 0.0], [1.0, 0.0]], dtype=np.float32)
        valid = np.ones((2, 2), dtype=bool)
        metrics = evaluation.corner_metrics(probabilities, target, valid)
        self.assertEqual(metrics["tp"], 1)
        self.assertEqual(metrics["fp"], 1)
        self.assertEqual(metrics["fn"], 1)
        self.assertAlmostEqual(evaluation.RUNTIME_CORNER_THRESHOLD, 0.56, places=6)

    def test_orientation_metric_is_sign_invariant(self):
        predicted = np.array([[[1.0, -1.0]], [[0.0, 0.0]]], dtype=np.float32)
        target = np.array([[[-1.0, 1.0]], [[0.0, 0.0]]], dtype=np.float32)
        valid = np.array([[True, True]])
        metrics = evaluation.orientation_metrics(predicted, target, valid)
        self.assertEqual(metrics["supportPixels"], 2)
        self.assertAlmostEqual(metrics["absCosineSum"], 2.0, places=6)
        self.assertAlmostEqual(metrics["angularErrorDegreesSum"], 0.0, places=6)

    def test_real_domains_are_explicitly_registered(self):
        self.assertIn("private-real-validation", evaluation.ALLOWED_DOMAINS)
        self.assertIn("private-real-held-out-test", evaluation.ALLOWED_DOMAINS)
        self.assertNotEqual(evaluation.DEFAULT_DOMAIN, "private-real-held-out-test")


if __name__ == "__main__":
    unittest.main()
