#!/usr/bin/env python3
"""Evaluate a trained Manzl real-plan candidate on the untouched final test split.

A pre-registered semantic policy is mandatory and must have been locked from private real validation
before any held-out test artifact exists. The final semantic attestation records policy PASS/FAIL but
still cannot make the model release-ready until the independent end-to-end geometry evidence passes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

import real_semantic_policy
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
        "--domain", "private-real-held-out-test",
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

    locked_policy, policy_sha = real_semantic_policy.load_locked_policy(
        args.policy,
        candidate,
        require_pre_test=True,
    )

    command = build_test_command(model_path, splits, result_path, args.size)
    assert_final_command_uses_only_test(command, splits)
    subprocess.run(command, check=True)
    if not result_path.is_file() or result_path.stat().st_size <= 0:
        raise RuntimeError("final held-out evaluator did not produce test metrics")
    metrics = json.loads(result_path.read_text(encoding="utf-8"))
    if metrics.get("schema") != 2 or metrics.get("domain") != "private-real-held-out-test":
        raise RuntimeError("held-out semantic evidence has incorrect evaluator provenance")
    if metrics.get("releaseReady") is not False:
        raise RuntimeError("semantic evaluator must never declare a release model")

    acceptance = real_semantic_policy.evaluate_metrics(locked_policy, metrics)
    semantic_pass = acceptance.get("semanticAcceptancePassed") is True
    test_fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
    final = {
        "schema": 3,
        "pipeline": "manzl-private-real-student-final-test",
        "model": model_path.name,
        "sha256": training_attestation["sha256"],
        "bytes": model_path.stat().st_size,
        "testSamples": preflight["testSamples"],
        "testSourceGroups": preflight["testSourceGroups"],
        "testSetFingerprint": test_fingerprint,
        "fingerprintContainsOnlyOpaqueSampleIds": True,
        "testMetrics": metrics,
        "semanticPolicySha256": policy_sha,
        "semanticAcceptanceEvaluation": acceptance,
        "testUsedForTraining": False,
        "testUsedForModelSelection": False,
        "testUsedForValidationMetrics": False,
        "testUsedForFinalEvaluation": True,
        "heldOutTestIntegrityVerified": True,
        "semanticTestCompleted": True,
        "semanticAcceptancePolicyLockedBeforeTest": True,
        "semanticAcceptancePolicyEvaluated": True,
        "semanticAcceptancePassed": semantic_pass,
        "geometryGatesEvaluatedByThisStep": False,
        "releaseReady": False,
        "reason": (
            "Semantic held-out acceptance passed; release still requires independent end-to-end geometry evidence."
            if semantic_pass
            else "Semantic held-out acceptance failed the pre-registered validation-derived policy."
        ),
    }
    attestation_path.write_text(json.dumps(final, indent=2, sort_keys=True), encoding="utf-8")
    return final


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--policy", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = evaluate(args)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
