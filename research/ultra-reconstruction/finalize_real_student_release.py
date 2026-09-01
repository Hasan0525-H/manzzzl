#!/usr/bin/env python3
"""Combine Manzl real-student release evidence for one exact ONNX candidate and test corpus.

Release succeeds only when training, semantic held-out evidence and production geometry evidence all
refer to the same exact ONNX digest and the same opaque NPZ artifacts that were bound to the candidate
at training time. The finalizer re-measures corpus scale, split fingerprints and semantic metrics; it
never trusts stored PASS booleans by themselves.
"""

from __future__ import annotations

import argparse
import json
import pathlib

import evaluate_real_student_test
import real_semantic_policy
import release_corpus_scale
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


def _same_json(left: dict, right: dict) -> bool:
    return real_semantic_policy.canonical_digest(left) == real_semantic_policy.canonical_digest(right)


def recompute_semantic_evidence(
    policy: dict,
    semantic: dict,
    model_sha256: str,
    expected_test_samples: int,
) -> tuple[dict, dict]:
    metrics = semantic.get("testMetrics")
    if not isinstance(metrics, dict) or metrics.get("schema") != 2 or metrics.get("domain") != "private-real-held-out-test":
        raise ValueError("semantic final-test attestation contains invalid held-out testMetrics")
    if metrics.get("releaseReady") is not False:
        raise ValueError("raw held-out testMetrics must remain non-release")
    metric_samples = metrics.get("samples")
    if isinstance(metric_samples, bool) or not isinstance(metric_samples, int):
        raise ValueError("raw held-out testMetrics must report an integer samples count")
    if metric_samples != expected_test_samples:
        raise ValueError(
            "raw semantic held-out metrics do not cover the exact full test split: "
            f"metrics.samples={metric_samples} expected={expected_test_samples}"
        )

    relative = real_semantic_policy.evaluate_metrics(policy, metrics)
    absolute = evaluate_real_student_test.absolute_semantic_quality(metrics)
    if relative.get("semanticAcceptancePassed") is not True:
        raise ValueError("recomputed relative semantic acceptance failed")
    if absolute.get("absoluteSemanticQualityPassed") is not True:
        raise ValueError("recomputed absolute semantic quality failed")
    if relative.get("policyModelSha256") != model_sha256:
        raise ValueError("recomputed relative semantic acceptance is bound to a different model")
    if relative.get("validationEvaluationSha256") != policy.get("validationEvaluationSha256"):
        raise ValueError("recomputed relative semantic acceptance is bound to different validation evidence")
    if absolute.get("qualityFloorVersion") != 1:
        raise ValueError("unexpected absolute semantic quality-floor version")

    stored_relative = semantic.get("semanticAcceptanceEvaluation")
    stored_absolute = semantic.get("absoluteSemanticQualityEvaluation")
    if not isinstance(stored_relative, dict) or not _same_json(stored_relative, relative):
        raise ValueError("stored semantic acceptance evaluation does not match recomputed held-out metrics")
    if not isinstance(stored_absolute, dict) or not _same_json(stored_absolute, absolute):
        raise ValueError("stored absolute semantic quality evaluation does not match recomputed held-out metrics")
    return relative, absolute


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
    corpus_scale = release_corpus_scale.require(preflight)
    model_path, training = evaluate_real_student_test.load_candidate(candidate)
    evaluate_real_student_test.assert_candidate_split_binding(training, preflight)

    model_sha256 = training["sha256"]
    model_bytes = model_path.stat().st_size
    test_fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
    test_artifact_fingerprint = preflight["opaqueSplitArtifactFingerprints"]["test"]
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
            "testArtifactFingerprint": test_artifact_fingerprint,
            "candidateSplitBindingsVerified": True,
            "splitArtifactsStableAcrossFinalSemanticEvaluation": True,
            "releaseCorpusScaleVerifiedAtFinalTest": True,
            "semanticMetricsExactHeldOutSampleCoverage": True,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "artifactFingerprintIsAggregateOnly": True,
            "semanticPolicySha256": policy_sha,
            "relativeSemanticAcceptancePassed": True,
            "absoluteSemanticQualityPassed": True,
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
    _, absolute = recompute_semantic_evidence(policy, semantic, model_sha256, test_samples)

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
            "candidateSplitBindingsVerified": True,
            "splitArtifactsStableAcrossGeometryVerification": True,
            "releaseCorpusScaleVerifiedAtGeometryGate": True,
            "geometryEvidenceBoundToExactModelDigest": True,
            "testSetFingerprint": test_fingerprint,
            "testArtifactFingerprint": test_artifact_fingerprint,
            "fingerprintContainsOnlyOpaqueSampleIds": True,
            "artifactFingerprintIsAggregateOnly": True,
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

    if semantic["testArtifactFingerprint"] != geometry["testArtifactFingerprint"]:
        raise ValueError("semantic and geometry evidence were produced from different held-out test artifacts")
    if semantic["testArtifactFingerprint"] != training["testArtifactFingerprint"]:
        raise ValueError("held-out evidence artifact fingerprint differs from the candidate training binding")

    postflight = verify_real_training_inputs.verify(splits)
    evaluate_real_student_test.assert_candidate_split_binding(training, postflight)
    if postflight["opaqueSplitArtifactFingerprints"] != preflight["opaqueSplitArtifactFingerprints"]:
        raise RuntimeError("real-plan split artifacts changed while final release evidence was combined")

    return {
        "schema": 2,
        "pipeline": "manzl-real-student-release-evidence-bundle",
        "model": model_path.name,
        "sha256": model_sha256,
        "bytes": model_bytes,
        "trainSourceGroups": preflight["trainSourceGroups"],
        "validationSourceGroups": preflight["validationSourceGroups"],
        "testSamples": test_samples,
        "testSourceGroups": test_groups,
        "testSetFingerprint": test_fingerprint,
        "heldOutArtifactFingerprint": test_artifact_fingerprint,
        "artifactFingerprintIsAggregateOnly": True,
        "semanticPolicySha256": policy_sha,
        "trainingAttestationVerified": True,
        "candidateArtifactIntegrityPassed": True,
        "candidateSplitBindingsVerified": True,
        "splitArtifactsStableAcrossFinalization": True,
        "heldOutCorpusIdentityMatchedAcrossEvidence": True,
        "heldOutArtifactIdentityMatchedAcrossEvidence": True,
        "releaseCorpusScalePassed": True,
        "releaseCorpusScalePolicyVersion": corpus_scale["policyVersion"],
        "releaseCorpusScaleRecomputedAtFinalize": True,
        "semanticMetricsExactHeldOutSampleCoverage": True,
        "semanticAcceptancePolicyLocked": True,
        "semanticAcceptancePolicyEvaluated": True,
        "relativeSemanticAcceptancePassed": True,
        "absoluteSemanticQualityPassed": True,
        "absoluteSemanticQualityFloorVersion": absolute["qualityFloorVersion"],
        "semanticEvidenceRecomputedAtFinalize": True,
        "semanticAcceptancePassed": True,
        "semanticHeldOutMeasurementCompleted": True,
        "geometryReleaseEvidencePassed": True,
        "allEvidenceBoundToExactModelDigest": True,
        "releaseEvidenceBundleComplete": True,
        "releaseReady": True,
        "blockingReason": None,
        "reason": (
            "The exact ONNX candidate passed on the exact release-scale NPZ artifacts bound at training time. "
            "Semantic and geometry evidence share the same held-out artifact fingerprint, full sample coverage, "
            "immutable semantic floors and production 2D-to-3D geometry gates."
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
