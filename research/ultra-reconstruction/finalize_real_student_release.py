#!/usr/bin/env python3
"""Combine Manzl real-student release evidence for one exact ONNX candidate.

Release succeeds only when the same model digest and opaque held-out corpus are covered by:
1) verified real training provenance,
2) a semantic policy locked from validation before test,
3) held-out semantic PASS against that policy, and
4) production runtime end-to-end geometry PASS.
"""

from __future__ import annotations

import argparse
import json
import pathlib

import evaluate_real_student_test
import real_semantic_policy
import verify_real_training_inputs


def load_json_object(path: pathlib.Path, label: str) -> dict:
    path = path.resolve()
    if not path.is_file():
        raise FileNotFoundError(f"{label} is missing: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def require_contract(payload: dict, required: dict, label: str) -> None:
    for key, expected in required.items():
        if payload.get(key) != expected:
            raise ValueError(
                f"{label} contract failed for {key}: {payload.get(key)!r} != {expected!r}"
            )


def finalize(
    splits: pathlib.Path,
    candidate: pathlib.Path,
    semantic_policy_path: pathlib.Path,
    semantic_attestation_path: pathlib.Path,
    geometry_attestation_path: pathlib.Path,
) -> dict:
    splits = splits.resolve()
    candidate = candidate.resolve()
    preflight = verify_real_training_inputs.verify(splits)
    model_path, training = evaluate_real_student_test.load_candidate(candidate)
    model_sha256 = training["sha256"]
    model_bytes = model_path.stat().st_size
    test_fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
    test_samples = preflight["testSamples"]
    test_groups = preflight["testSourceGroups"]

    policy, policy_sha = real_semantic_policy.load_locked_policy(
        semantic_policy_path,
        candidate,
        require_pre_test=False,
    )

    semantic = load_json_object(semantic_attestation_path, "semantic final-test attestation")
    require_contract(
        semantic,
        {
            "schema": 3,
            "pipeline": "manzl-private-real-student-final-test",
            "model": model_path.name,
            "sha256": model_sha256,
            "bytes": model_bytes,
            "testSamples": test_samples,
            "testSourceGroups": test_groups,
            "testSetFingerprint": test_fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "semanticPolicySha256": policy_sha,
            "testUsedForTraining": False,
            "testUsedForModelSelection": False,
            "testUsedForValidationMetrics": False,
            "testUsedForFinalEvaluation": True,
            "heldOutTestIntegrityVerified": True,
            "semanticTestCompleted": True,
            "semanticAcceptancePolicyLockedBeforeTest": True,
            "semanticAcceptancePolicyEvaluated": True,
            "semanticAcceptancePassed": True,
            "geometryGatesEvaluatedByThisStep": False,
            "releaseReady": False,
        },
        "semantic final-test attestation",
    )
    metrics = semantic.get("testMetrics")
    if not isinstance(metrics, dict) or metrics.get("schema") != 2 or metrics.get("domain") != "private-real-held-out-test":
        raise ValueError("semantic final-test attestation contains invalid held-out testMetrics")
    acceptance = semantic.get("semanticAcceptanceEvaluation")
    if not isinstance(acceptance, dict) or acceptance.get("semanticAcceptancePassed") is not True:
        raise ValueError("semantic acceptance evaluation is missing or failed")
    if acceptance.get("policyModelSha256") != model_sha256:
        raise ValueError("semantic acceptance evaluation is bound to a different model")
    if acceptance.get("validationEvaluationSha256") != policy.get("validationEvaluationSha256"):
        raise ValueError("semantic acceptance evaluation is bound to different validation evidence")

    geometry = load_json_object(geometry_attestation_path, "geometry release attestation")
    require_contract(
        geometry,
        {
            "schema": 3,
            "pipeline": "manzl-held-out-real-plan-end-to-end-geometry-release-gate",
            "model": model_path.name,
            "modelSha256": model_sha256,
            "modelBytes": model_bytes,
            "candidateTrainingAttestationVerified": True,
            "geometryEvidenceBoundToExactModelDigest": True,
            "testSetFingerprint": test_fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "testSamples": test_samples,
            "evidenceSamples": test_samples,
            "exactHeldOutSampleCoverage": True,
            "runtimeThresholdsDuplicated": False,
            "allGeometryFidelityPassed": True,
            "allGeometryQualityGatesPassed": True,
            "allReconstructionReadinessGatesPassed": True,
            "allEndToEnd2dTo3dGeometryGatesPassed": True,
            "releaseGeometryEvidencePassed": True,
            "releaseReady": False,
        },
        "geometry release attestation",
    )

    return {
        "schema": 2,
        "pipeline": "manzl-real-student-release-evidence-bundle",
        "model": model_path.name,
        "sha256": model_sha256,
        "bytes": model_bytes,
        "testSamples": test_samples,
        "testSourceGroups": test_groups,
        "testSetFingerprint": test_fingerprint,
        "semanticPolicySha256": policy_sha,
        "trainingAttestationVerified": True,
        "candidateArtifactIntegrityPassed": True,
        "heldOutCorpusIdentityMatchedAcrossEvidence": True,
        "semanticAcceptancePolicyLocked": True,
        "semanticAcceptancePolicyEvaluated": True,
        "semanticAcceptancePassed": True,
        "semanticHeldOutMeasurementCompleted": True,
        "geometryReleaseEvidencePassed": True,
        "allEvidenceBoundToExactModelDigest": True,
        "releaseEvidenceBundleComplete": True,
        "releaseReady": True,
        "blockingReason": None,
        "reason": (
            "The exact ONNX candidate passed the pre-registered semantic held-out policy and all production "
            "end-to-end geometry gates on the exact opaque held-out corpus. The student model is release-ready; "
            "APK packaging remains a separate build step."
        ),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--semantic-policy", type=pathlib.Path, required=True)
    parser.add_argument("--semantic-attestation", type=pathlib.Path, required=True)
    parser.add_argument("--geometry-attestation", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.output.exists():
        raise FileExistsError(f"release evidence output already exists; refusing overwrite: {args.output}")
    report = finalize(
        args.splits,
        args.candidate,
        args.semantic_policy,
        args.semantic_attestation,
        args.geometry_attestation,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
