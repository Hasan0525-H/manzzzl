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


if __name__ == "__main__":
    unittest.main()
