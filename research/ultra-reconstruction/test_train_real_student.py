from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import train_real_student as trainer  # noqa: E402


class TrainRealStudentIsolationTest(unittest.TestCase):
    def make_args(self, root: pathlib.Path) -> argparse.Namespace:
        return argparse.Namespace(
            splits=root / "splits",
            output=root / "output",
            size=512,
            width=32,
            epochs=60,
            batch=4,
            workers=2,
            lr=2e-4,
            seed=439,
            patience=8,
            min_improvement=1e-4,
            cpu=True,
        )

    def test_train_and_validation_commands_never_reference_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            args = self.make_args(root)
            staging = root / "staging"
            train_command = trainer.build_train_command(args, staging)
            validation_command = trainer.build_validation_command(args, staging)
            trainer.assert_test_isolation(
                [train_command, validation_command],
                args.splits / "test",
            )
            self.assertIn(str(args.splits / "train"), train_command)
            self.assertIn(str(args.splits / "validation"), train_command)
            self.assertNotIn(str(args.splits / "test"), train_command)
            self.assertIn(str(args.splits / "validation"), validation_command)
            self.assertNotIn(str(args.splits / "test"), validation_command)
            domain_index = validation_command.index("--domain")
            self.assertEqual(validation_command[domain_index + 1], "private-real-validation")

    def test_test_path_injected_into_any_command_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            test_root = root / "splits" / "test"
            with self.assertRaisesRegex(RuntimeError, "unexpectedly references held-out test split"):
                trainer.assert_test_isolation(
                    [[sys.executable, "fake.py", "--data", str(test_root)]],
                    test_root,
                )


if __name__ == "__main__":
    unittest.main()
