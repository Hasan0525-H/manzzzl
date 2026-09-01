from __future__ import annotations

import hashlib
import json
import pathlib
import tempfile
import unittest

import real_semantic_policy as policy


class RealSemanticPolicyTest(unittest.TestCase):
    def validation(self) -> dict:
        per_class = {}
        for index, name in enumerate(("background",) + policy.CRITICAL_CLASSES):
            tp = 800 + index * 5
            fp = 50 + index
            fn = 60 + index
            per_class[name] = {
                "present": True,
                "iou": tp / (tp + fp + fn),
                "precision": tp / (tp + fp),
                "recall": tp / (tp + fn),
                "supportPixels": tp + fn,
                "tp": tp,
                "fp": fp,
                "fn": fn,
                "intersection": tp,
                "union": tp + fp + fn,
            }
        return {
            "schema": 2,
            "domain": "private-real-validation",
            "samples": 24,
            "inputSize": 512,
            "semantic": {"meanIoU": 0.85, "perClass": per_class},
            "corners": {
                "runtimeThreshold": 0.56,
                "thresholdMatchesAndroidCornerSnap": True,
                "precision": 0.9,
                "recall": 0.88,
                "f1": 0.89,
                "meanAbsoluteError": 0.05,
                "supportPixels": 900,
                "evaluatedPixels": 9000,
                "tp": 800,
                "fp": 90,
                "fn": 110,
            },
            "orientation": {
                "signInvariant": True,
                "meanAbsCosine": 0.94,
                "meanAngularErrorDegrees": 8.0,
                "supportPixels": 4000,
            },
            "releaseReady": False,
        }

    def make_candidate(self, root: pathlib.Path) -> pathlib.Path:
        candidate = root / "candidate"
        candidate.mkdir()
        model = candidate / "manzl_reconstruction_student.onnx"
        model.write_bytes(b"real-model-bytes")
        validation = self.validation()
        training = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": model.name,
            "sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "bytes": model.stat().st_size,
            "validationEvaluation": validation,
            "releaseReady": False,
        }
        (candidate / "real-training-attestation.json").write_text(json.dumps(training), encoding="utf-8")
        return candidate

    def test_policy_locks_from_validation_before_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = self.make_candidate(pathlib.Path(tmp))
            locked = policy.build_policy(candidate)
            self.assertTrue(locked["lockedFromValidationOnly"])
            self.assertFalse(locked["heldOutMetricsConsulted"])
            self.assertEqual(locked["criticalClasses"], list(policy.CRITICAL_CLASSES))
            self.assertGreater(locked["thresholds"]["semanticClasses"]["door"]["recallMin"], 0.0)

    def test_policy_lock_is_rejected_after_test_artifact_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = self.make_candidate(pathlib.Path(tmp))
            (candidate / "final-test-eval.json").write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "before any held-out test artifact"):
                policy.build_policy(candidate)

    def test_validation_like_test_metrics_pass_locked_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = self.make_candidate(pathlib.Path(tmp))
            locked = policy.build_policy(candidate)
            test = self.validation()
            test["domain"] = "private-real-held-out-test"
            result = policy.evaluate_metrics(locked, test)
            self.assertTrue(result["semanticAcceptancePassed"])

    def test_collapsed_critical_class_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = self.make_candidate(pathlib.Path(tmp))
            locked = policy.build_policy(candidate)
            test = self.validation()
            test["domain"] = "private-real-held-out-test"
            test["semantic"]["perClass"]["door"]["recall"] = 0.0
            result = policy.evaluate_metrics(locked, test)
            self.assertFalse(result["semanticAcceptancePassed"])
            self.assertFalse(result["checks"]["class:door:recall"]["passed"])

    def test_wilson_floor_is_below_observed_rate(self):
        observed = 80 / 100
        self.assertLess(policy.wilson_lower(80, 100), observed)
        self.assertGreater(policy.wilson_lower(80, 100), 0.0)


if __name__ == "__main__":
    unittest.main()
