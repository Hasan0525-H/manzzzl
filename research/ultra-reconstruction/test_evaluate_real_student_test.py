from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import evaluate_real_student_test as evaluator  # noqa: E402


class EvaluateRealStudentTestIsolationTest(unittest.TestCase):
    def test_final_command_references_only_test_split(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = root / "splits"
            model = root / "candidate" / "manzl_reconstruction_student.onnx"
            output = root / "candidate" / "final-test-eval.json"
            command = evaluator.build_test_command(model, splits, output, 512)
            evaluator.assert_final_command_uses_only_test(command, splits)
            self.assertIn(str(splits / "test"), command)
            self.assertNotIn(str(splits / "train"), command)
            self.assertNotIn(str(splits / "validation"), command)
            domain_index = command.index("--domain")
            self.assertEqual(command[domain_index + 1], "private-real-held-out-test")

    def test_train_or_validation_in_final_command_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = root / "splits"
            test_path = splits / "test"
            bad = [
                sys.executable,
                "fake.py",
                "--data", str(test_path),
                "--also", str(splits / "validation"),
            ]
            with self.assertRaisesRegex(RuntimeError, "unexpectedly references train/validation"):
                evaluator.assert_final_command_uses_only_test(bad, splits)

    def test_command_without_test_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            splits = root / "splits"
            with self.assertRaisesRegex(RuntimeError, "does not reference the held-out test split"):
                evaluator.assert_final_command_uses_only_test(
                    [sys.executable, "fake.py", "--data", str(splits / "validation")],
                    splits,
                )


class AbsoluteSemanticQualityTest(unittest.TestCase):
    def good_metrics(self) -> dict:
        per_class = {}
        for name, floors in evaluator.ABSOLUTE_CLASS_FLOORS.items():
            per_class[name] = {
                "present": True,
                "iou": min(1.0, floors["iou"] + 0.03),
                "precision": min(1.0, floors["precision"] + 0.03),
                "recall": min(1.0, floors["recall"] + 0.03),
            }
        return {
            "schema": 2,
            "domain": "private-real-held-out-test",
            "releaseReady": False,
            "semantic": {"perClass": per_class},
            "corners": {
                "precision": evaluator.ABSOLUTE_CORNER_PRECISION_MIN + 0.03,
                "recall": evaluator.ABSOLUTE_CORNER_RECALL_MIN + 0.03,
                "thresholdMatchesAndroidCornerSnap": True,
            },
            "orientation": {
                "signInvariant": True,
                "supportPixels": 1000,
                "meanAbsCosine": evaluator.ABSOLUTE_ORIENTATION_COSINE_MIN + 0.02,
                "meanAngularErrorDegrees": evaluator.ABSOLUTE_ORIENTATION_ANGLE_MAX_DEGREES - 1.0,
            },
        }

    def test_strong_held_out_metrics_clear_absolute_gate(self):
        report = evaluator.absolute_semantic_quality(self.good_metrics())
        self.assertTrue(report["absoluteSemanticQualityPassed"])
        self.assertTrue(all(item["passed"] for item in report["checks"].values()))

    def test_weak_door_recall_cannot_hide_behind_validation_policy(self):
        metrics = self.good_metrics()
        metrics["semantic"]["perClass"]["door"]["recall"] = evaluator.ABSOLUTE_CLASS_FLOORS["door"]["recall"] - 0.01
        report = evaluator.absolute_semantic_quality(metrics)
        self.assertFalse(report["absoluteSemanticQualityPassed"])
        check = report["checks"]["class:door:recall"]
        self.assertFalse(check["passed"])
        self.assertEqual(check["minimum"], evaluator.ABSOLUTE_CLASS_FLOORS["door"]["recall"])

    def test_missing_critical_class_fails_absolute_gate(self):
        metrics = self.good_metrics()
        metrics["semantic"]["perClass"]["shaft"]["present"] = False
        report = evaluator.absolute_semantic_quality(metrics)
        self.assertFalse(report["absoluteSemanticQualityPassed"])
        self.assertFalse(report["checks"]["class:shaft:present"]["passed"])

    def test_corner_or_orientation_weakness_fails_absolute_gate(self):
        metrics = self.good_metrics()
        metrics["corners"]["precision"] = evaluator.ABSOLUTE_CORNER_PRECISION_MIN - 0.01
        metrics["orientation"]["meanAngularErrorDegrees"] = evaluator.ABSOLUTE_ORIENTATION_ANGLE_MAX_DEGREES + 0.5
        report = evaluator.absolute_semantic_quality(metrics)
        self.assertFalse(report["absoluteSemanticQualityPassed"])
        self.assertFalse(report["checks"]["corners:precision"]["passed"])
        self.assertFalse(report["checks"]["orientation:meanAngularErrorDegrees"]["passed"])


if __name__ == "__main__":
    unittest.main()
