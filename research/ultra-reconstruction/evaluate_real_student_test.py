#!/usr/bin/env python3
"""Evaluate a trained Manzl real-plan candidate on the untouched final test split.

A pre-registered semantic policy is mandatory and must have been locked from private real validation
before any held-out test artifact exists. Passing that relative policy is necessary but not sufficient:
a weak validation model must never be able to manufacture a weak release threshold for itself. The
held-out test must therefore also clear fixed absolute engineering floors for every critical semantic
class, corners and wall orientation. The final semantic attestation still cannot make the model
release-ready until the independent end-to-end geometry evidence passes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import subprocess
import sys

import real_semantic_policy
import verify_real_training_inputs

HERE = pathlib.Path(__file__).resolve().parent

# Product-quality floors. Validation-derived Wilson/Hoeffding bounds may only raise the effective bar;
# they can never lower these minimums. These are intentionally strict because a semantic miss can
# create or remove physical geometry downstream.
ABSOLUTE_CLASS_FLOORS = {
    "wall_face": {"iou": 0.85, "precision": 0.92, "recall": 0.92},
    "door": {"iou": 0.55, "precision": 0.80, "recall": 0.75},
    "window": {"iou": 0.55, "precision": 0.80, "recall": 0.75},
    "stair": {"iou": 0.60, "precision": 0.85, "recall": 0.80},
    "column": {"iou": 0.55, "precision": 0.85, "recall": 0.75},
    "room_boundary": {"iou": 0.75, "precision": 0.90, "recall": 0.88},
    "courtyard": {"iou": 0.65, "precision": 0.85, "recall": 0.80},
    "shaft": {"iou": 0.60, "precision": 0.85, "recall": 0.78},
}
ABSOLUTE_CORNER_PRECISION_MIN = 0.80
ABSOLUTE_CORNER_RECALL_MIN = 0.80
ABSOLUTE_ORIENTATION_COSINE_MIN = 0.95
ABSOLUTE_ORIENTATION_ANGLE_MAX_DEGREES = 10.0


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


def _finite_ratio(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"absolute semantic metric {label} must be numeric")
    actual = float(value)
    if not math.isfinite(actual) or not 0.0 <= actual <= 1.0:
        raise ValueError(f"absolute semantic metric {label} must be finite within [0,1]")
    return actual


def absolute_semantic_quality(metrics: dict) -> dict:
    """Apply immutable release floors that validation can never weaken."""
    if metrics.get("schema") != 2 or metrics.get("domain") != "private-real-held-out-test":
        raise ValueError("absolute semantic quality requires private-real-held-out-test schema 2 metrics")
    if metrics.get("releaseReady") is not False:
        raise ValueError("raw semantic metrics must remain non-release")

    semantic = metrics.get("semantic")
    if not isinstance(semantic, dict) or not isinstance(semantic.get("perClass"), dict):
        raise ValueError("absolute semantic quality requires per-class evidence")

    checks: dict[str, dict] = {}
    all_pass = True
    for class_name, floors in ABSOLUTE_CLASS_FLOORS.items():
        item = semantic["perClass"].get(class_name)
        present = isinstance(item, dict) and item.get("present") is True
        checks[f"class:{class_name}:present"] = {"passed": present}
        all_pass = all_pass and present
        if not present:
            continue
        assert isinstance(item, dict)
        for metric_name, minimum in floors.items():
            actual = _finite_ratio(item.get(metric_name), f"{class_name}.{metric_name}")
            passed = actual + 1e-12 >= minimum
            checks[f"class:{class_name}:{metric_name}"] = {
                "actual": actual,
                "minimum": minimum,
                "passed": passed,
            }
            all_pass = all_pass and passed

    corners = metrics.get("corners")
    if not isinstance(corners, dict) or corners.get("thresholdMatchesAndroidCornerSnap") is not True:
        raise ValueError("absolute semantic quality requires corners at the Android runtime threshold")
    for metric_name, minimum in (
        ("precision", ABSOLUTE_CORNER_PRECISION_MIN),
        ("recall", ABSOLUTE_CORNER_RECALL_MIN),
    ):
        actual = _finite_ratio(corners.get(metric_name), f"corners.{metric_name}")
        passed = actual + 1e-12 >= minimum
        checks[f"corners:{metric_name}"] = {
            "actual": actual,
            "minimum": minimum,
            "passed": passed,
        }
        all_pass = all_pass and passed

    orientation = metrics.get("orientation")
    if not isinstance(orientation, dict) or orientation.get("signInvariant") is not True:
        raise ValueError("absolute semantic quality requires sign-invariant orientation evidence")
    support = orientation.get("supportPixels")
    support_pass = isinstance(support, int) and not isinstance(support, bool) and support > 0
    checks["orientation:support"] = {
        "actual": support,
        "minimumExclusive": 0,
        "passed": support_pass,
    }
    all_pass = all_pass and support_pass

    cosine = _finite_ratio(orientation.get("meanAbsCosine"), "orientation.meanAbsCosine")
    cosine_pass = cosine + 1e-12 >= ABSOLUTE_ORIENTATION_COSINE_MIN
    checks["orientation:meanAbsCosine"] = {
        "actual": cosine,
        "minimum": ABSOLUTE_ORIENTATION_COSINE_MIN,
        "passed": cosine_pass,
    }
    all_pass = all_pass and cosine_pass

    angle = orientation.get("meanAngularErrorDegrees")
    if isinstance(angle, bool) or not isinstance(angle, (int, float)) or not math.isfinite(float(angle)):
        raise ValueError("absolute semantic metric orientation.meanAngularErrorDegrees must be finite numeric")
    angle_value = float(angle)
    if not 0.0 <= angle_value <= 90.0:
        raise ValueError("absolute semantic metric orientation.meanAngularErrorDegrees must be within [0,90]")
    angle_pass = angle_value <= ABSOLUTE_ORIENTATION_ANGLE_MAX_DEGREES + 1e-12
    checks["orientation:meanAngularErrorDegrees"] = {
        "actual": angle_value,
        "maximum": ABSOLUTE_ORIENTATION_ANGLE_MAX_DEGREES,
        "passed": angle_pass,
    }
    all_pass = all_pass and angle_pass

    return {
        "schema": 1,
        "pipeline": "manzl-absolute-real-semantic-quality-gate",
        "qualityFloorVersion": 1,
        "criticalClasses": list(ABSOLUTE_CLASS_FLOORS),
        "checks": checks,
        "absoluteSemanticQualityPassed": bool(all_pass),
        "releaseReady": False,
    }


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
    absolute_quality = absolute_semantic_quality(metrics)
    relative_pass = acceptance.get("semanticAcceptancePassed") is True
    absolute_pass = absolute_quality.get("absoluteSemanticQualityPassed") is True
    semantic_pass = relative_pass and absolute_pass
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
        "absoluteSemanticQualityEvaluation": absolute_quality,
        "relativeSemanticAcceptancePassed": relative_pass,
        "absoluteSemanticQualityPassed": absolute_pass,
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
            "Semantic held-out acceptance and immutable absolute quality floors passed; release still requires independent end-to-end geometry evidence."
            if semantic_pass
            else (
                "Semantic held-out test failed immutable absolute product-quality floors."
                if relative_pass and not absolute_pass
                else "Semantic held-out test failed the pre-registered validation-derived policy and/or immutable absolute product-quality floors."
            )
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
