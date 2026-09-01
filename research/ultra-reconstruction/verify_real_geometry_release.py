#!/usr/bin/env python3
"""Verify held-out real-plan end-to-end geometry evidence against the exact bound test artifacts.

Android production runtime remains the authority for wall fidelity, mismatch/topology integrity and
reconstruction readiness. Each opaque test sample exports one ``*.geometry.json`` record bound to the
exact ONNX SHA256. This verifier additionally requires the current train/validation/test membership and
aggregate NPZ artifact fingerprints to match the candidate attestation created at training time, and
rechecks them after reading geometry evidence so the benchmark cannot mutate during verification.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re

import evaluate_real_student_test
import release_corpus_scale
import verify_real_training_inputs

OPAQUE_SAMPLE_ID = re.compile(r"^sample-[0-9a-f]{32}$")
EVIDENCE_FILE = re.compile(r"^(sample-[0-9a-f]{32})\.geometry\.json$")
RATIO_FIELDS = (
    "fidelityScore",
    "wallCoverage",
    "wallPrecision",
    "endpointSupport",
    "trustedRoomCoverage",
)
COUNT_FIELDS = (
    "topologyNearMissCount",
    "unresolvedOpeningCount",
    "unsupportedVerticalVoidCount",
    "unsupportedRoomBoundaryCount",
    "trustedRoomCount",
)
FORBIDDEN_SOURCE_KEYS = {
    "sourcePath",
    "sourcePaths",
    "sourceFilename",
    "sourceFilenames",
    "rawRasterHash",
    "rawRasterSha256",
    "userLabel",
    "clientName",
}


def expected_test_ids(split_root: pathlib.Path) -> set[str]:
    files = verify_real_training_inputs.discover_split(split_root, "test")
    ids = {path.stem for path in files}
    if not ids or any(OPAQUE_SAMPLE_ID.fullmatch(sample_id) is None for sample_id in ids):
        raise RuntimeError("held-out test split does not contain only opaque sample ids")
    return ids


def discover_evidence(evidence_root: pathlib.Path) -> dict[str, pathlib.Path]:
    evidence_root = evidence_root.resolve()
    if not evidence_root.is_dir():
        raise FileNotFoundError(f"geometry evidence directory is missing: {evidence_root}")
    nested = [path for path in evidence_root.rglob("*.json") if path.parent != evidence_root]
    if nested:
        raise RuntimeError("geometry release evidence must be a flat directory")

    result: dict[str, pathlib.Path] = {}
    for path in sorted(evidence_root.glob("*.json")):
        match = EVIDENCE_FILE.fullmatch(path.name)
        if match is None:
            raise RuntimeError(f"non-opaque or unexpected geometry evidence filename: {path.name}")
        sample_id = match.group(1)
        if sample_id in result:
            raise RuntimeError(f"duplicate geometry evidence for {sample_id}")
        result[sample_id] = path
    if not result:
        raise RuntimeError("geometry release evidence directory contains no samples")
    return result


def _reject_forbidden_source_fields(payload: dict) -> None:
    present = sorted(FORBIDDEN_SOURCE_KEYS.intersection(payload))
    if present:
        raise ValueError(f"geometry evidence contains forbidden private source fields: {present}")


def _ratio(payload: dict, key: str) -> float:
    value = payload.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"geometry evidence field {key} must be numeric")
    value = float(value)
    if not (0.0 <= value <= 1.0):
        raise ValueError(f"geometry evidence field {key} must be within [0, 1]")
    return value


def _count(payload: dict, key: str) -> int:
    value = payload.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"geometry evidence field {key} must be a non-negative integer")
    return value


def load_sample(path: pathlib.Path, expected_id: str, expected_model_sha256: str) -> dict:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"geometry evidence must be a JSON object: {path.name}")
    _reject_forbidden_source_fields(payload)

    required = {
        "schema": 2,
        "pipeline": "manzl-runtime-end-to-end-geometry-gates",
        "sampleId": expected_id,
        "modelSha256": expected_model_sha256,
        "sourcePathsStored": False,
        "sourceFilenamesStored": False,
        "rawRasterHashesStored": False,
        "runtimeThresholdsDuplicated": False,
        "geometryFidelityPass": True,
        "geometryQualityGatePassed": True,
        "reconstructionReadinessGatePassed": True,
        "endToEnd2dTo3dGeometryGatesPassed": True,
        "releaseReady": False,
    }
    for key, expected in required.items():
        if payload.get(key) != expected:
            raise ValueError(
                f"geometry release contract failed for {expected_id} field {key}: "
                f"{payload.get(key)!r} != {expected!r}"
            )
    if payload.get("fidelityStatus") != "PASS":
        raise ValueError(f"geometry fidelity status is not PASS for {expected_id}")
    for key in RATIO_FIELDS:
        _ratio(payload, key)
    for key in COUNT_FIELDS:
        _count(payload, key)
    return payload


def verify(split_root: pathlib.Path, evidence_root: pathlib.Path, candidate: pathlib.Path) -> dict:
    split_root = split_root.resolve()
    candidate = candidate.resolve()
    preflight = verify_real_training_inputs.verify(split_root)
    release_corpus_scale.require(preflight)
    expected = expected_test_ids(split_root)
    model_path, training_attestation = evaluate_real_student_test.load_candidate(candidate)
    evaluate_real_student_test.assert_candidate_split_binding(training_attestation, preflight)
    model_sha256 = training_attestation["sha256"]
    test_set_fingerprint = preflight["opaqueSplitSetFingerprints"]["test"]
    test_artifact_fingerprint = preflight["opaqueSplitArtifactFingerprints"]["test"]

    evidence = discover_evidence(evidence_root)
    actual = set(evidence)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing or unexpected:
        raise RuntimeError(
            "geometry evidence set does not exactly match held-out test split; "
            f"missing={missing[:8]} unexpected={unexpected[:8]}"
        )

    samples = [load_sample(evidence[sample_id], sample_id, model_sha256) for sample_id in sorted(expected)]

    # Re-measure the private split after consuming geometry evidence. Any mutation invalidates the run.
    postflight = verify_real_training_inputs.verify(split_root)
    evaluate_real_student_test.assert_candidate_split_binding(training_attestation, postflight)
    if postflight["opaqueSplitArtifactFingerprints"] != preflight["opaqueSplitArtifactFingerprints"]:
        raise RuntimeError("real-plan split artifacts changed while geometry evidence was being verified")

    mins = {key: min(float(sample[key]) for sample in samples) for key in RATIO_FIELDS}
    means = {key: sum(float(sample[key]) for sample in samples) / len(samples) for key in RATIO_FIELDS}

    return {
        "schema": 3,
        "pipeline": "manzl-held-out-real-plan-end-to-end-geometry-release-gate",
        "model": model_path.name,
        "modelSha256": model_sha256,
        "modelBytes": model_path.stat().st_size,
        "candidateTrainingAttestationVerified": True,
        "candidateSplitBindingsVerified": True,
        "splitArtifactsStableAcrossGeometryVerification": True,
        "releaseCorpusScaleVerifiedAtGeometryGate": True,
        "geometryEvidenceBoundToExactModelDigest": True,
        "testSetFingerprint": test_set_fingerprint,
        "testArtifactFingerprint": test_artifact_fingerprint,
        "fingerprintContainsOnlyOpaqueSampleIds": True,
        "artifactFingerprintIsAggregateOnly": True,
        "testSamples": len(samples),
        "evidenceSamples": len(samples),
        "exactHeldOutSampleCoverage": True,
        "runtimeThresholdsDuplicated": False,
        "allGeometryFidelityPassed": True,
        "allGeometryQualityGatesPassed": True,
        "allReconstructionReadinessGatesPassed": True,
        "allEndToEnd2dTo3dGeometryGatesPassed": True,
        "minimumObservedMetrics": mins,
        "meanObservedMetrics": means,
        "releaseGeometryEvidencePassed": True,
        "releaseReady": False,
        "reason": (
            "Held-out geometry evidence passed production runtime decisions for the exact NPZ artifacts bound "
            "to the candidate at training time, with exact opaque sample coverage and model digest identity."
        ),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--evidence", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = verify(args.splits, args.evidence, args.candidate)
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
