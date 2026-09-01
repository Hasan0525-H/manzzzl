#!/usr/bin/env python3
"""Evaluate a trained Manzl real-plan candidate on the untouched final test split.

This is intentionally separate from ``train_real_student.py``. The candidate attestation must prove that
test data was not used for training, model selection, or validation metrics. Only then is the ONNX model
evaluated against ``splits/test``.

Passing this semantic measurement still does not make the model release-ready: Manzl's end-to-end
2D->3D geometry gates and a separately locked semantic acceptance policy remain mandatory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

import verify_real_training_inputs

HERE = pathlib.Path(__file__).resolve().parent


def load_candidate(candidate: pathlib.Path) -> tuple[pathlib.Path, dict]:
    candidate = candidate.resolve()
    attestation_path = candidate / "real-training-attestation.json"
    model_path = candidate / "manzl_reconstruction_student.onnx"
    if not attestation_path.is_file():
        raise FileNotFoundError(f"real-training attestation is missing: {attestation_path}")
    if not model_path.is_file():
        raise FileNotFoundError(f"candidate ONNX model is missing: {model_path}")
    attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
    if not isinstance(attestation, dict) or attestation.get("schema") != 1:
        raise ValueError("real-training attestation must use schema 1")
    required = {
        "pipeline": "manzl-private-real-student-release-candidate",
        "trainingSplit": "train",
        "modelSelectionSplit": "validation",
        "testSplitPresentAndVerified": True,
        "testUsedForTraining": False,
        "testUsedForModelSelection": False,
        "testUsedForValidationMetrics": False,
        "testReservedForFinalEvaluation": True,
        "realTrainingPreflightPassed": True,
        "releaseReady": False,
    }
    for key, expected in required.items():
        if attestation.get(key) != expected:
            raise ValueError(
                f"candidate attestation contract failed for {key}: "
                f"{attestation.get(key)!r} != {expected!r}"
            )
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if attestation.get("sha256") != digest:
        raise RuntimeError("candidate ONNX SHA256 does not match its real-training attestation")
    if int(attestation.get("bytes", -1)) != model_path.stat().st_size:
        raise RuntimeError("candidate ONNX byte size does not match its real-training attestation")
    return model_path, attestation


def build_test_command(model_path: pathlib.Path, splits: pathlib.Path, output: pathlib.Path, size: int) -> list[str]:
    return [
        sys.executable,
        str(HERE / "evaluate_student_onnx.py"),
        "--model", str(model_path),
        "--data", str(splits / "test"),
        "--output", str(output),
        "--size", str(size),
    ]


def assert_final_command_uses_only_test(command: list[str], splits: pathlib.Path) -> None:
    train = str((splits / "train").resolve())
    validation = str((splits / "validation").resolve())
    test = str((splits / "test").resolve())
    resolved = []
    for token in command:
        try:
            resolved.append(str(pathlib.Path(token).resolve()))
        except (OSError, ValueError):
            resolved.append(token)
    if test not in resolved:
        raise RuntimeError("final real-plan evaluation command does not reference the held-out test split")
    if train in resolved or validation in resolved:
        raise RuntimeError("final real-plan evaluation command unexpectedly references train/validation")


def evaluate(args: argparse.Namespace) -> dict:
    splits = args.splits.resolve()
    candidate = args.candidate.resolve()
    preflight = verify_real_training_inputs.verify(splits)
    if preflight.get("testReservedForFinalEvaluation") is not True:
        raise RuntimeError("real split preflight does not reserve test for final evaluation")

    model_path, training_attestation = load_candidate(candidate)
    result_path = candidate / "final-test-eval.json"
    attestation_path = candidate / "final-test-attestation.json"
    if result_path.exists() or attestation_path.exists():
        raise FileExistsError("final test evaluation already exists; refusing to overwrite held-out evidence")

    command = build_test_command(model_path, splits, result_path, args.size)
    assert_final_command_uses_only_test(command, splits)
    subprocess.run(command, check=True)
    if not result_path.is_file() or result_path.stat().st_size <= 0:
        raise RuntimeError("final held-out evaluator did not produce test metrics")
    metrics = json.loads(result_path.read_text(encoding="utf-8"))

    test_fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
    final = {
        "schema": 2,
        "pipeline": "manzl-private-real-student-final-test",
        "model": model_path.name,
        "sha256": training_attestation["sha256"],
        "bytes": model_path.stat().st_size,
        "testSamples": preflight["testSamples"],
        "testSourceGroups": preflight["testSourceGroups"],
        "testSetFingerprint": test_fingerprint,
        "fingerprintContainsOnlyOpaqueSampleIds": True,
        "testMetrics": metrics,
        "testUsedForTraining": False,
        "testUsedForModelSelection": False,
        "testUsedForValidationMetrics": False,
        "testUsedForFinalEvaluation": True,
        "heldOutTestIntegrityVerified": True,
        "semanticTestCompleted": True,
        "semanticAcceptancePolicyEvaluated": False,
        "semanticAcceptancePassed": False,
        "geometryGatesEvaluatedByThisStep": False,
        "releaseReady": False,
        "reason": (
            "The untouched real-plan semantic test has been measured and bound to the exact opaque test "
            "set and ONNX digest. Release still requires a locked semantic acceptance policy plus the "
            "separate measured end-to-end 2D-to-3D geometry gates."
        ),
    }
    attestation_path.write_text(json.dumps(final, indent=2, sort_keys=True), encoding="utf-8")
    return final


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = evaluate(args)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
