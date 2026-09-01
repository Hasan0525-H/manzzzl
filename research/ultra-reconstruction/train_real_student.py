#!/usr/bin/env python3
"""Train a Manzl release-candidate student from verified private real-plan splits.

This wrapper is the only supported bridge from private real-corpus materialization into
``train_student.py``. It exposes only ``train`` and ``validation`` to the trainer. ``test`` is verified
but never passed to training, model selection, or validation evaluation. A release candidate is started
only when the corpus already meets the immutable release-scale policy, avoiding expensive training on a
benchmark that can never be promoted. Validation evidence must cover the exact validation split.

The wrapper never makes a model release-ready. Final held-out semantic and end-to-end geometry gates are
separate release steps.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

import release_corpus_scale
import verify_real_training_inputs

HERE = pathlib.Path(__file__).resolve().parent


def build_train_command(args: argparse.Namespace, staging: pathlib.Path) -> list[str]:
    return [
        sys.executable,
        str(HERE / "train_student.py"),
        "--data", str(args.splits / "train"),
        "--val-data", str(args.splits / "validation"),
        "--output", str(staging / "student.pt"),
        "--onnx", str(staging / "manzl_reconstruction_student.onnx"),
        "--size", str(args.size),
        "--width", str(args.width),
        "--epochs", str(args.epochs),
        "--batch", str(args.batch),
        "--workers", str(args.workers),
        "--lr", str(args.lr),
        "--seed", str(args.seed),
        "--patience", str(args.patience),
        "--min-improvement", str(args.min_improvement),
        *(["--cpu"] if args.cpu else []),
    ]


def build_validation_command(args: argparse.Namespace, staging: pathlib.Path) -> list[str]:
    return [
        sys.executable,
        str(HERE / "evaluate_student_onnx.py"),
        "--model", str(staging / "manzl_reconstruction_student.onnx"),
        "--data", str(args.splits / "validation"),
        "--output", str(staging / "validation-eval.json"),
        "--size", str(args.size),
        "--domain", "private-real-validation",
    ]


def assert_test_isolation(commands: list[list[str]], test_root: pathlib.Path) -> None:
    test_text = str(test_root.resolve())
    for command in commands:
        resolved_tokens = []
        for token in command:
            try:
                resolved_tokens.append(str(pathlib.Path(token).resolve()))
            except (OSError, ValueError):
                resolved_tokens.append(token)
        if test_text in resolved_tokens or any(token == str(test_root) for token in command):
            raise RuntimeError("release-candidate trainer command unexpectedly references held-out test split")


def require_validation_full_coverage(validation: dict, expected_samples: int) -> None:
    samples = validation.get("samples")
    if isinstance(samples, bool) or not isinstance(samples, int):
        raise RuntimeError("real validation evidence must report an integer samples count")
    if samples != expected_samples:
        raise RuntimeError(
            "real validation evidence does not cover the exact validation split: "
            f"metrics.samples={samples} expected={expected_samples}"
        )


def train(args: argparse.Namespace) -> dict:
    args.splits = args.splits.resolve()
    args.output = args.output.resolve()
    preflight = verify_real_training_inputs.verify(args.splits)
    if preflight.get("trainerMayRead") != ["train", "validation"]:
        raise RuntimeError("real-training preflight does not authorize exactly train+validation")
    if preflight.get("trainerMustNotRead") != ["test"]:
        raise RuntimeError("real-training preflight does not reserve test exclusively")
    corpus_scale = release_corpus_scale.require(preflight)

    if args.output.exists():
        raise FileExistsError(f"real student output already exists; refusing overwrite: {args.output}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(prefix=f".{args.output.name}.staging-", dir=str(args.output.parent)))

    train_command = build_train_command(args, staging)
    validation_command = build_validation_command(args, staging)
    assert_test_isolation([train_command, validation_command], args.splits / "test")

    try:
        (staging / "real-training-preflight.json").write_text(
            json.dumps(preflight, indent=2, sort_keys=True), encoding="utf-8"
        )
        subprocess.run(train_command, check=True)
        subprocess.run(validation_command, check=True)

        model_path = staging / "manzl_reconstruction_student.onnx"
        metadata_path = staging / "manzl_reconstruction_student.json"
        validation_path = staging / "validation-eval.json"
        for required in (model_path, metadata_path, validation_path):
            if not required.is_file() or required.stat().st_size <= 0:
                raise RuntimeError(f"real student training did not produce required output: {required.name}")

        validation = json.loads(validation_path.read_text(encoding="utf-8"))
        if validation.get("schema") != 2 or validation.get("domain") != "private-real-validation":
            raise RuntimeError("real validation evidence has incorrect evaluator provenance")
        if validation.get("releaseReady") is not False:
            raise RuntimeError("validation evaluator must never declare a release model")
        require_validation_full_coverage(validation, int(preflight["validationSamples"]))

        digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
        attestation = {
            "schema": 1,
            "pipeline": "manzl-private-real-student-release-candidate",
            "model": model_path.name,
            "sha256": digest,
            "bytes": model_path.stat().st_size,
            "trainingSource": "privacy-preserving strict three-teacher consensus on private real floor plans",
            "trainingSplit": "train",
            "modelSelectionSplit": "validation",
            "validationEvaluation": validation,
            "validationMetricsExactSplitCoverage": True,
            "releaseCorpusScalePreflightPassed": True,
            "releaseCorpusScalePolicyVersion": corpus_scale["policyVersion"],
            "testSplitPresentAndVerified": True,
            "testUsedForTraining": False,
            "testUsedForModelSelection": False,
            "testUsedForValidationMetrics": False,
            "testReservedForFinalEvaluation": True,
            "realTrainingPreflightPassed": True,
            "releaseReady": False,
            "reason": (
                "This release candidate was trained only after the real corpus met release-scale requirements, "
                "and its validation metrics cover the exact validation split. The untouched test split and "
                "end-to-end 2D-to-3D gates must still pass separately."
            ),
        }
        (staging / "real-training-attestation.json").write_text(
            json.dumps(attestation, indent=2, sort_keys=True), encoding="utf-8"
        )
        staging.rename(args.output)
        return attestation
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--width", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=439)
    parser.add_argument("--patience", type=int, default=8)
    parser.add_argument("--min-improvement", type=float, default=1e-4)
    parser.add_argument("--cpu", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    attestation = train(args)
    print(json.dumps(attestation, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
