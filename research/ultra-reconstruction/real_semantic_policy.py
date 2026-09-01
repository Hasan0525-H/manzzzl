#!/usr/bin/env python3
"""Pre-register and evaluate Manzl real-student semantic acceptance policy.

The policy is locked from private real validation evidence before any held-out test artifact exists.
Critical-class and corner floors use 95% Wilson lower confidence bounds derived from validation
confusion counts. Orientation uses a conservative Hoeffding bound with validation plan count as the
effective sample size. No held-out metric is consulted while locking the policy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib

CRITICAL_CLASSES = (
    "wall_face",
    "door",
    "window",
    "stair",
    "column",
    "room_boundary",
    "courtyard",
    "shaft",
)
CONFIDENCE_LEVEL = 0.95
WILSON_Z = 1.959963984540054


def canonical_digest(payload: dict) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def wilson_lower(successes: int, total: int, z: float = WILSON_Z) -> float:
    if total <= 0 or successes < 0 or successes > total:
        raise ValueError("invalid Wilson count pair")
    p = successes / total
    z2 = z * z
    denominator = 1.0 + z2 / total
    center = p + z2 / (2.0 * total)
    margin = z * math.sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total)
    return max(0.0, (center - margin) / denominator)


def hoeffding_lower(mean: float, effective_n: int, alpha: float = 0.05) -> float:
    if effective_n <= 0 or not math.isfinite(mean) or not 0.0 <= mean <= 1.0:
        raise ValueError("invalid Hoeffding inputs")
    margin = math.sqrt(math.log(1.0 / alpha) / (2.0 * effective_n))
    return max(0.0, mean - margin)


def _load_json(path: pathlib.Path, label: str) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"{label} is missing: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def _load_candidate(candidate: pathlib.Path) -> tuple[pathlib.Path, dict]:
    candidate = candidate.resolve()
    model = candidate / "manzl_reconstruction_student.onnx"
    training = _load_json(candidate / "real-training-attestation.json", "real-training attestation")
    if training.get("schema") != 1 or training.get("pipeline") != "manzl-private-real-student-release-candidate":
        raise ValueError("candidate is not a verified real-plan student release candidate")
    if training.get("releaseReady") is not False:
        raise ValueError("training attestation must remain non-release")
    if not model.is_file() or model.stat().st_size <= 0:
        raise FileNotFoundError(f"candidate ONNX model is missing: {model}")
    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    if training.get("sha256") != digest or int(training.get("bytes", -1)) != model.stat().st_size:
        raise RuntimeError("candidate model does not match real-training attestation")
    return model, training


def _validated_class_counts(item: dict, name: str) -> tuple[int, int, int]:
    if item.get("present") is not True:
        raise RuntimeError(f"validation corpus does not contain critical semantic class: {name}")
    values = []
    for key in ("tp", "fp", "fn"):
        value = item.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(f"validation semantic class {name} has invalid {key}")
        values.append(value)
    tp, fp, fn = values
    if tp + fn <= 0 or tp + fp + fn <= 0:
        raise RuntimeError(f"validation semantic class {name} has no measurable support")
    return tp, fp, fn


def build_policy(candidate: pathlib.Path) -> dict:
    candidate = candidate.resolve()
    for forbidden in ("final-test-eval.json", "final-test-attestation.json"):
        if (candidate / forbidden).exists():
            raise RuntimeError("semantic policy must be locked before any held-out test artifact exists")

    model, training = _load_candidate(candidate)
    validation = training.get("validationEvaluation")
    if not isinstance(validation, dict):
        raise ValueError("training attestation contains no validation evaluation")
    if validation.get("schema") != 2 or validation.get("domain") != "private-real-validation":
        raise ValueError("semantic policy can only be locked from private-real-validation schema 2 evidence")
    if validation.get("releaseReady") is not False:
        raise ValueError("validation evidence must never be release-ready")
    samples = validation.get("samples")
    if isinstance(samples, bool) or not isinstance(samples, int) or samples <= 0:
        raise ValueError("validation evaluation must report a positive plan count")

    semantic = validation.get("semantic")
    if not isinstance(semantic, dict) or not isinstance(semantic.get("perClass"), dict):
        raise ValueError("validation evaluation is missing semantic per-class evidence")
    class_thresholds = {}
    for name in CRITICAL_CLASSES:
        item = semantic["perClass"].get(name)
        if not isinstance(item, dict):
            raise RuntimeError(f"validation evaluation is missing critical semantic class: {name}")
        tp, fp, fn = _validated_class_counts(item, name)
        class_thresholds[name] = {
            "iouMin": wilson_lower(tp, tp + fp + fn),
            "precisionMin": wilson_lower(tp, tp + fp) if tp + fp > 0 else 0.0,
            "recallMin": wilson_lower(tp, tp + fn),
            "validationTp": tp,
            "validationFp": fp,
            "validationFn": fn,
        }

    corners = validation.get("corners")
    if not isinstance(corners, dict):
        raise ValueError("validation evaluation is missing corner evidence")
    corner_tp = int(corners.get("tp", -1))
    corner_fp = int(corners.get("fp", -1))
    corner_fn = int(corners.get("fn", -1))
    if min(corner_tp, corner_fp, corner_fn) < 0 or corner_tp + corner_fn <= 0:
        raise RuntimeError("validation corner evidence has no measurable positive support")
    if corners.get("thresholdMatchesAndroidCornerSnap") is not True:
        raise RuntimeError("validation corner evidence does not use the Android runtime threshold")

    orientation = validation.get("orientation")
    if not isinstance(orientation, dict) or orientation.get("signInvariant") is not True:
        raise ValueError("validation evaluation is missing sign-invariant orientation evidence")
    orientation_support = orientation.get("supportPixels")
    mean_cosine = orientation.get("meanAbsCosine")
    mean_angle = orientation.get("meanAngularErrorDegrees")
    if isinstance(orientation_support, bool) or not isinstance(orientation_support, int) or orientation_support <= 0:
        raise RuntimeError("validation orientation evidence has no support")
    if not isinstance(mean_cosine, (int, float)) or not 0.0 <= float(mean_cosine) <= 1.0:
        raise ValueError("validation orientation meanAbsCosine is invalid")
    if not isinstance(mean_angle, (int, float)) or not 0.0 <= float(mean_angle) <= 90.0:
        raise ValueError("validation orientation meanAngularErrorDegrees is invalid")
    cosine_floor = hoeffding_lower(float(mean_cosine), samples)
    angle_score = 1.0 - float(mean_angle) / 90.0
    angle_score_floor = hoeffding_lower(angle_score, samples)

    return {
        "schema": 1,
        "pipeline": "manzl-real-semantic-acceptance-policy",
        "model": model.name,
        "modelSha256": training["sha256"],
        "modelBytes": model.stat().st_size,
        "validationEvaluationSha256": canonical_digest(validation),
        "validationDomain": "private-real-validation",
        "validationSamples": samples,
        "criticalClasses": list(CRITICAL_CLASSES),
        "confidenceMethod": "wilson-95-for-binomial-head-metrics-plus-hoeffding-95-orientation-plan-count",
        "confidenceLevel": CONFIDENCE_LEVEL,
        "thresholds": {
            "semanticClasses": class_thresholds,
            "corners": {
                "precisionMin": wilson_lower(corner_tp, corner_tp + corner_fp) if corner_tp + corner_fp > 0 else 0.0,
                "recallMin": wilson_lower(corner_tp, corner_tp + corner_fn),
                "validationTp": corner_tp,
                "validationFp": corner_fp,
                "validationFn": corner_fn,
                "runtimeThreshold": corners.get("runtimeThreshold"),
            },
            "orientation": {
                "meanAbsCosineMin": cosine_floor,
                "meanAngularErrorDegreesMax": (1.0 - angle_score_floor) * 90.0,
                "effectiveN": samples,
                "requiresPositiveSupport": True,
            },
        },
        "lockedFromValidationOnly": True,
        "lockedBeforeHeldOutTestArtifacts": True,
        "heldOutMetricsConsulted": False,
        "releaseReady": False,
    }


def write_locked_policy(candidate: pathlib.Path, output: pathlib.Path) -> dict:
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"semantic policy already exists; refusing overwrite: {output}")
    policy = build_policy(candidate)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(policy, indent=2, sort_keys=True), encoding="utf-8")
    return policy


def load_locked_policy(path: pathlib.Path, candidate: pathlib.Path, require_pre_test: bool) -> tuple[dict, str]:
    path = path.resolve()
    raw = path.read_bytes() if path.is_file() else b""
    if not raw:
        raise FileNotFoundError(f"locked semantic policy is missing: {path}")
    policy = json.loads(raw.decode("utf-8"))
    if not isinstance(policy, dict):
        raise ValueError("locked semantic policy must be a JSON object")
    model, training = _load_candidate(candidate)
    required = {
        "schema": 1,
        "pipeline": "manzl-real-semantic-acceptance-policy",
        "model": model.name,
        "modelSha256": training["sha256"],
        "modelBytes": model.stat().st_size,
        "validationDomain": "private-real-validation",
        "criticalClasses": list(CRITICAL_CLASSES),
        "confidenceLevel": CONFIDENCE_LEVEL,
        "lockedFromValidationOnly": True,
        "lockedBeforeHeldOutTestArtifacts": True,
        "heldOutMetricsConsulted": False,
        "releaseReady": False,
    }
    for key, expected in required.items():
        if policy.get(key) != expected:
            raise ValueError(f"locked semantic policy contract failed for {key}")
    validation = training.get("validationEvaluation")
    if policy.get("validationEvaluationSha256") != canonical_digest(validation):
        raise RuntimeError("semantic policy is not bound to this candidate validation evidence")
    if require_pre_test:
        for forbidden in ("final-test-eval.json", "final-test-attestation.json"):
            if (candidate.resolve() / forbidden).exists():
                raise RuntimeError("held-out artifacts exist before semantic policy verification")
    return policy, hashlib.sha256(raw).hexdigest()


def _metric_at_least(value, floor: float, label: str) -> tuple[bool, dict]:
    if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(float(value)):
        raise ValueError(f"test metric {label} is invalid")
    actual = float(value)
    return actual + 1e-12 >= floor, {"actual": actual, "minimum": floor}


def evaluate_metrics(policy: dict, metrics: dict) -> dict:
    if metrics.get("schema") != 2 or metrics.get("domain") != "private-real-held-out-test":
        raise ValueError("semantic acceptance requires private-real-held-out-test schema 2 metrics")
    if metrics.get("releaseReady") is not False:
        raise ValueError("raw semantic metrics must not claim release readiness")
    semantic = metrics.get("semantic")
    if not isinstance(semantic, dict) or not isinstance(semantic.get("perClass"), dict):
        raise ValueError("held-out semantic evidence is incomplete")

    checks = {}
    all_pass = True
    thresholds = policy["thresholds"]
    for name in policy["criticalClasses"]:
        item = semantic["perClass"].get(name)
        if not isinstance(item, dict) or item.get("present") is not True:
            checks[f"class:{name}:present"] = {"passed": False}
            all_pass = False
            continue
        class_floor = thresholds["semanticClasses"][name]
        for metric_name, floor_name in (("iou", "iouMin"), ("precision", "precisionMin"), ("recall", "recallMin")):
            passed, detail = _metric_at_least(item.get(metric_name), float(class_floor[floor_name]), f"{name}.{metric_name}")
            detail["passed"] = passed
            checks[f"class:{name}:{metric_name}"] = detail
            all_pass = all_pass and passed

    corners = metrics.get("corners")
    if not isinstance(corners, dict) or corners.get("thresholdMatchesAndroidCornerSnap") is not True:
        raise ValueError("held-out corner evidence is incomplete or uses the wrong runtime threshold")
    for metric_name, floor_name in (("precision", "precisionMin"), ("recall", "recallMin")):
        passed, detail = _metric_at_least(corners.get(metric_name), float(thresholds["corners"][floor_name]), f"corners.{metric_name}")
        detail["passed"] = passed
        checks[f"corners:{metric_name}"] = detail
        all_pass = all_pass and passed

    orientation = metrics.get("orientation")
    if not isinstance(orientation, dict) or orientation.get("signInvariant") is not True:
        raise ValueError("held-out orientation evidence is incomplete")
    support = orientation.get("supportPixels")
    support_pass = isinstance(support, int) and not isinstance(support, bool) and support > 0
    checks["orientation:support"] = {"actual": support, "minimumExclusive": 0, "passed": support_pass}
    all_pass = all_pass and support_pass
    passed, detail = _metric_at_least(
        orientation.get("meanAbsCosine"),
        float(thresholds["orientation"]["meanAbsCosineMin"]),
        "orientation.meanAbsCosine",
    )
    detail["passed"] = passed
    checks["orientation:meanAbsCosine"] = detail
    all_pass = all_pass and passed
    max_angle = float(thresholds["orientation"]["meanAngularErrorDegreesMax"])
    angle = orientation.get("meanAngularErrorDegrees")
    if not isinstance(angle, (int, float)) or isinstance(angle, bool) or not math.isfinite(float(angle)):
        raise ValueError("test metric orientation.meanAngularErrorDegrees is invalid")
    angle_pass = float(angle) <= max_angle + 1e-12
    checks["orientation:meanAngularErrorDegrees"] = {
        "actual": float(angle),
        "maximum": max_angle,
        "passed": angle_pass,
    }
    all_pass = all_pass and angle_pass

    return {
        "schema": 1,
        "pipeline": "manzl-real-semantic-acceptance-evaluation",
        "policyModelSha256": policy["modelSha256"],
        "validationEvaluationSha256": policy["validationEvaluationSha256"],
        "checks": checks,
        "semanticAcceptancePassed": bool(all_pass),
        "releaseReady": False,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    lock = sub.add_parser("lock")
    lock.add_argument("--candidate", type=pathlib.Path, required=True)
    lock.add_argument("--output", type=pathlib.Path, required=True)
    check = sub.add_parser("evaluate")
    check.add_argument("--candidate", type=pathlib.Path, required=True)
    check.add_argument("--policy", type=pathlib.Path, required=True)
    check.add_argument("--metrics", type=pathlib.Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.command == "lock":
        report = write_locked_policy(args.candidate, args.output)
    else:
        policy, policy_sha = load_locked_policy(args.policy, args.candidate, require_pre_test=False)
        metrics = _load_json(args.metrics, "held-out semantic metrics")
        report = evaluate_metrics(policy, metrics)
        report["policySha256"] = policy_sha
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
