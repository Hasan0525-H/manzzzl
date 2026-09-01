#!/usr/bin/env python3
"""Combine Manzl real-student release evidence without silently inventing acceptance criteria.

This is a fail-closed evidence combiner, not a release switch. It proves that the training attestation,
semantic held-out measurement and runtime end-to-end geometry evidence all refer to the exact same ONNX
candidate and the exact same privacy-safe held-out test-set membership. The repository does not yet have
a locked semantic acceptance policy for the real student, so this combiner must keep ``releaseReady``
false until that policy is added and independently enforced.
"""

from __future__ import annotations

import argparse
import json
import pathlib

import evaluate_real_student_test
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

    semantic = load_json_object(semantic_attestation_path, "semantic final-test attestation")
    require_contract(
        semantic,
        {
            "schema": 2,
            "pipeline": "manzl-private-real-student-final-test",
            "model": model_path.name,
            "sha256": model_sha256,
            "bytes": model_bytes,
            "testSamples": test_samples,
            "testSourceGroups": test_groups,
            "testSetFingerprint": test_fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
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
        },
        "semantic final-test attestation",
    )
    if not isinstance(semantic.get("testMetrics"), dict) or not semantic["testMetrics"]:
        raise ValueError("semantic final-test attestation contains no measured testMetrics")

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
        "schema": 1,
        "pipeline": "manzl-real-student-release-evidence-bundle",
        "model": model_path.name,
        "sha256": model_sha256,
        "bytes": model_bytes,
        "testSamples": test_samples,
        "testSourceGroups": test_groups,
        "testSetFingerprint": test_fingerprint,
        "trainingAttestationVerified": True,
        "candidateArtifactIntegrityPassed": True,
        "heldOutCorpusIdentityMatchedAcrossEvidence": True,
        "semanticHeldOutMeasurementCompleted": True,
        "geometryReleaseEvidencePassed": True,
        "allEvidenceBoundToExactModelDigest": True,
        "semanticAcceptancePolicyLocked": False,
        "semanticAcceptancePolicyEvaluated": False,
        "semanticAcceptancePassed": False,
        "releaseEvidenceBundleComplete": True,
        "releaseReady": False,
        "blockingReason": "semantic-acceptance-policy-not-locked",
        "reason": (
            "Training, semantic held-out measurement and end-to-end geometry evidence are internally "
            "consistent for the exact ONNX digest and exact opaque test set. Release remains blocked "
            "because no repository-owned semantic acceptance policy has yet been locked and evaluated."
        ),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
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
        args.semantic_attestation,
        args.geometry_attestation,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
